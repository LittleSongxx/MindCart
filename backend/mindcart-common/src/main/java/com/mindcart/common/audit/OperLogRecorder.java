package com.mindcart.common.audit;

/**
 * 审计日志的落库出口。放在 common 由各服务提供实现：
 * 每个服务有自己的库，审计表也随之分库（与 ADR-0001 的服务边界一致，
 * 不为了"集中看日志"去跨库写同一个表）。
 *
 * 未提供实现时（如还没接审计的服务、或单元测试）由
 * {@link OperationLogAspect} 静默跳过，不影响业务。
 */
public interface OperLogRecorder {

    /**
     * 写入一条审计记录。
     *
     * 实现必须自己吞掉异常：审计留痕失败不能反过来让一次成功的业务操作变成 500。
     * 落库失败只允许记一条 WARN。
     */
    void record(OperLogEntry entry);
}
