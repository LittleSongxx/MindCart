package com.smartore.voice.config;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.FileRefreshableDataSource;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.util.List;

/**
 * Sentinel 规则装配。
 * <p>
 * 两道资源：
 * - vs-entry（语音交互入口）：并发线程数 + QPS 双上限。突发流量超出快速失败，
 * 由 OrchestratorService 给礼貌降级文案，防止并发把 LLM 供应商连接打满后集体阻塞雪崩。
 * - vs-llm-call（LLM 调用）：慢调用比例 + 异常比例熔断。语音链路对延迟敏感，
 * 慢调用阈值按秒级设定（默认 8s，与 .block(timeout) 对齐）；熔断期内直接抛
 * DegradeException，到期自动半开试探，避免对故障供应商持续加压。
 * <p>
 * 规则动态化：voice-shopping.resilience.rules-file 指向一个 JSON 文件时，
 * 从文件加载并每 3s 自动刷新（调参不重启），文件格式
 * {"flow":[FlowRule...], "degrade":[DegradeRule...]}；未配置文件时加载代码默认值。
 * 未来接入 Nacos 时仅需把 FileRefreshableDataSource 换成 NacosDataSource。
 */
@Configuration
@Slf4j
public class ResilienceConfig {

    /** 流量入口资源名 */
    public static final String RES_ENTRY = "vs-entry";
    /** LLM 调用资源名：熔断统计挂在这条资源上 */
    public static final String RES_LLM = "vs-llm-call";

    @Value("${voice-shopping.resilience.rules-file:}")
    private String rulesFile;

    @Value("${voice-shopping.resilience.max-concurrency:32}")
    private int maxConcurrency;

    @Value("${voice-shopping.resilience.qps-limit:20}")
    private double qpsLimit;

    @Value("${voice-shopping.resilience.slow-call-ms:8000}")
    private long slowCallMs;

    @Value("${voice-shopping.resilience.circuit-break-ratio:0.5}")
    private double circuitBreakRatio;

    @Value("${voice-shopping.resilience.circuit-break-seconds:20}")
    private int circuitBreakSeconds;

    @PostConstruct
    public void loadRules() {
        if (rulesFile != null && !rulesFile.isBlank() && new File(rulesFile).isFile()) {
            loadFromFile(new File(rulesFile));
            return;
        }
        loadDefaults();
    }

    /** 代码默认规则：无外部文件时的兜底，保证裸启动也有熔断限流 */
    private void loadDefaults() {
        FlowRule concurrencyRule = new FlowRule(RES_ENTRY);
        concurrencyRule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        concurrencyRule.setCount(maxConcurrency);

        FlowRule qpsRule = new FlowRule(RES_ENTRY);
        qpsRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        qpsRule.setCount(qpsLimit);

        DegradeRule slowCallRule = new DegradeRule(RES_LLM);
        slowCallRule.setGrade(RuleConstant.DEGRADE_GRADE_RT);
        slowCallRule.setCount(slowCallMs);
        slowCallRule.setSlowRatioThreshold(circuitBreakRatio);
        slowCallRule.setTimeWindow(circuitBreakSeconds);
        slowCallRule.setMinRequestAmount(5);
        slowCallRule.setStatIntervalMs(60_000);

        DegradeRule exceptionRule = new DegradeRule(RES_LLM);
        exceptionRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        exceptionRule.setCount(circuitBreakRatio);
        exceptionRule.setTimeWindow(circuitBreakSeconds);
        exceptionRule.setMinRequestAmount(5);
        exceptionRule.setStatIntervalMs(60_000);

        FlowRuleManager.loadRules(List.of(concurrencyRule, qpsRule));
        DegradeRuleManager.loadRules(List.of(slowCallRule, exceptionRule));

        log.info("Sentinel 默认规则已加载: 并发上限={}, QPS上限={}, 慢调用阈值={}ms, 熔断比例={}, 熔断时长={}s",
                maxConcurrency, qpsLimit, slowCallMs, circuitBreakRatio, circuitBreakSeconds);
    }

    /** 文件数据源：每 3s 自动刷新，规则更新无需重启；文件损坏回退代码默认值 */
    private void loadFromFile(File file) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Converter<String, List<FlowRule>> flowParser = src -> parseRules(mapper, src, "flow", FlowRule.class);
        Converter<String, List<DegradeRule>> degradeParser = src -> parseRules(mapper, src, "degrade", DegradeRule.class);
        try {
            FileRefreshableDataSource<List<FlowRule>> flowDs =
                    new FileRefreshableDataSource<>(file, flowParser, 3000);
            FlowRuleManager.register2Property(flowDs.getProperty());
            FileRefreshableDataSource<List<DegradeRule>> degradeDs =
                    new FileRefreshableDataSource<>(file, degradeParser, 3000);
            DegradeRuleManager.register2Property(degradeDs.getProperty());
            log.info("Sentinel 规则已从文件加载并启用 3s 自动刷新: {}", file.getAbsolutePath());
        } catch (Exception e) {
            // 规则文件损坏不应阻断启动：回退代码默认值
            log.error("Sentinel 规则文件加载失败，回退默认规则: {}", file.getAbsolutePath(), e);
            loadDefaults();
        }
    }

    private static <T> List<T> parseRules(com.fasterxml.jackson.databind.ObjectMapper mapper,
                                          String src, String section, Class<T> ruleType) {
        try {
            String array = mapper.readTree(src).path(section).toString();
            return mapper.readerForListOf(ruleType).readValue(array);
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 Sentinel " + section + " 规则失败", e);
        }
    }
}
