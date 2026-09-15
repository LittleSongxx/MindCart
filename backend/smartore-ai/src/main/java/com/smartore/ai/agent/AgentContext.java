package com.smartore.ai.agent;

import com.smartore.trade.api.OrderStatsVO;

/**
 * 单次 Agent 运行的上下文：任务归属用户 + 懒加载的用户画像。
 * 工具执行时按需取用，避免每个工具都打一次下游查询。
 */
public class AgentContext {

    private final Integer userId;
    private volatile OrderStatsVO cachedOrderStats;
    private volatile boolean orderStatsLoaded;

    public AgentContext(Integer userId) {
        this.userId = userId;
    }

    public Integer getUserId() {
        return userId;
    }

    public OrderStatsVO getCachedOrderStats() {
        return cachedOrderStats;
    }

    public void setCachedOrderStats(OrderStatsVO stats) {
        this.cachedOrderStats = stats;
        this.orderStatsLoaded = true;
    }

    public boolean isOrderStatsLoaded() {
        return orderStatsLoaded;
    }
}
