package com.smartore.ai.agent.tools;

import cn.hutool.json.JSONObject;
import com.smartore.ai.agent.AgentContext;
import com.smartore.ai.agent.AgentTool;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.Result;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.trade.api.OrderFeignClient;
import com.smartore.trade.api.OrderStatsVO;
import com.smartore.user.api.UserFeignClient;
import com.smartore.user.api.UserVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 读取用户画像：账号信息（用户服务）+ 订单统计（交易服务，上下文内缓存一次） */
@Component
public class QueryUserProfileTool implements AgentTool {

    @Resource
    private UserFeignClient userClient;
    @Resource
    private OrderFeignClient orderClient;

    @Override
    public String name() {
        return "query_user_profile";
    }

    @Override
    public String label() {
        return "读取用户画像";
    }

    @Override
    public String description() {
        return "读取当前用户的画像：累计订单数、累计消费、账户余额、最近订单状态";
    }

    @Override
    public JSONObject parametersSchema() {
        return ToolSchemas.object(new JSONObject());
    }

    @Override
    public String execute(JSONObject arguments, AgentContext context) {
        Integer userId = context.getUserId();
        if (userId == null) {
            return "当前任务未选择用户，无用户画像。";
        }
        UserVO user = unwrap(userClient.getById(userId));
        if (user == null) {
            return "未查询到用户画像。";
        }
        OrderStatsVO stats = context.isOrderStatsLoaded()
                ? context.getCachedOrderStats()
                : loadAndCache(context);
        return "用户：" + user.getName()
                + "，累计订单数：" + (stats == null ? 0 : stats.getOrderCount())
                + "，累计消费：" + (stats == null ? 0 : stats.getPaidAmount())
                + "，账户余额：" + user.getBalance()
                + "，最近订单状态：" + (stats == null ? "无" : stats.getLatestOrderStatus());
    }

    private OrderStatsVO loadAndCache(AgentContext context) {
        try {
            OrderStatsVO stats = unwrap(orderClient.statsOfUser(context.getUserId()));
            context.setCachedOrderStats(stats);
            return stats;
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T unwrap(Result<T> result) {
        if (result == null || !ResultCodeEnum.SUCCESS.getCode().equals(result.getCode())) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR,
                    result == null ? "下游服务不可用" : result.getMsg());
        }
        return result.getData();
    }
}
