package com.smartore.goods.audit;

import com.smartore.common.audit.OperLogEntry;
import com.smartore.common.audit.OperLogRecorder;
import com.smartore.goods.mapper.OperLogMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * goods 的审计落库实现。
 *
 * 切面在业务方法返回后调用本实现，此时业务事务已结束，因此审计写入是独立事务——
 * 业务事务即使回滚，留痕也会保留下来，这正是"失败也要留痕"要的效果。
 */
@Component
public class GoodsOperLogRecorder implements OperLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(GoodsOperLogRecorder.class);

    @Resource
    private OperLogMapper operLogMapper;

    @Override
    public void record(OperLogEntry entry) {
        try {
            operLogMapper.insert(entry);
        } catch (Exception e) {
            // 审计失败不能影响业务；切面外层还有一层兜底，这里是双保险
            log.warn("审计日志落库失败（不影响业务）：{}", e.getMessage());
        }
    }
}
