package com.smartore.voice.dto;

import java.util.List;
import java.util.Map;

/**
 * @param actionFrame 需要前端代执行的动作帧（如下单/取消订单），null 表示无。
 *                    交易事实只在 Smartore 业务服务产生：语音确认后由前端
 *                    以用户自己的登录态走现成下单链路，结果经 WS 控制帧回传。
 */
public record EmotionResult(
        String speechText,
        List<RecommendedItem> displayBlocks,
        Map<String, Object> actionFrame
) {
    public EmotionResult(String speechText, List<RecommendedItem> displayBlocks) {
        this(speechText, displayBlocks, null);
    }
}
