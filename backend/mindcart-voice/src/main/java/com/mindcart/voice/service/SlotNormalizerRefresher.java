package com.mindcart.voice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位归一化的数据源刷新：DB 品类集（distinct category_l2）+ yml 同义词表。
 * 启动即刷 + 每小时兜底刷（品类/配置变更无需重启），SlotNormalizer 保持无状态可单测。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotNormalizerRefresher {

    private final JdbcTemplate jdbc;

    /** 形如 "跑步鞋:跑鞋,蓝牙耳机:耳机,机械表:手表"（键=LLM 常见抽取原词，值=category_l2） */
    @Value("${voice-shopping.normalize.category-synonyms:}")
    private String synonymsConfig;

    @jakarta.annotation.PostConstruct
    @Scheduled(fixedDelay = 3600_000)
    public void refresh() {
        try {
            List<String> cats = jdbc.queryForList(
                    "SELECT DISTINCT category_l2 FROM voice_product WHERE status='ON_SALE'", String.class);
            Map<String, String> syn = new HashMap<>();
            if (synonymsConfig != null && !synonymsConfig.isBlank()) {
                for (String kv : synonymsConfig.split(",")) {
                    int i = kv.indexOf(':');
                    if (i > 0) syn.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
                }
            }
            SlotNormalizer.refresh(cats, syn);
            log.info("[Normalize] 品类集 {} 个 + 同义词 {} 条已加载", cats.size(), syn.size());
        } catch (Exception e) {
            log.warn("[Normalize] 刷新失败（沿用上次数据）: {}", e.getMessage());
        }
    }
}
