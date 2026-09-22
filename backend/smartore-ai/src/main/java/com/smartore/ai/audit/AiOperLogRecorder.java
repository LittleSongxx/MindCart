package com.smartore.ai.audit;

import com.smartore.ai.mapper.OperLogMapper;
import com.smartore.common.audit.OperLogEntry;
import com.smartore.common.audit.OperLogRecorder;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI 侧的审计落库实现。
 *
 * 这里的留痕重点不是"改了配置"，而是"改了会影响所有回答的配置"：
 * 模型 Key/地址、提示词模板、知识库条目与切片——它们被改动后，全站问答与导购的输出都会变。
 */
@Component
public class AiOperLogRecorder implements OperLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiOperLogRecorder.class);

    @Resource
    private OperLogMapper operLogMapper;

    @Override
    public void record(OperLogEntry entry) {
        try {
            operLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("审计日志落库失败（不影响业务）：{}", e.getMessage());
        }
    }
}
