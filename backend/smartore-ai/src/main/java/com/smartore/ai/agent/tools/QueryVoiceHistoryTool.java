package com.smartore.ai.agent.tools;

import cn.hutool.json.JSONObject;
import com.smartore.ai.agent.AgentContext;
import com.smartore.ai.agent.AgentTool;
import com.smartore.ai.integration.VoiceFeignClient;
import com.smartore.common.result.Result;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 统一视图工具：让文本导购 Agent 看得到语音导购通道发生的事。
 * <p>
 * 此前两条 AI 链路各自记账——用户在语音里问过什么、语音里下了哪单，
 * 文本侧完全不知道（问"我语音里买过什么"只能瞎答或拒答）。
 * 本工具经内部契约只读拉取语音域摘要，注册即生效（AgentExecutor 收集所有 AgentTool Bean）。
 */
@Component
public class QueryVoiceHistoryTool implements AgentTool {

    @Resource
    private VoiceFeignClient voiceClient;

    @Override
    public String name() {
        return "query_voice_history";
    }

    @Override
    public String label() {
        return "读取语音通道记录";
    }

    @Override
    public String description() {
        return "读取当前用户在【语音导购】通道的记录：最近几次语音会话（用户问了什么、AI 最后怎么答的、"
                + "该次是否成交）以及语音下单的订单。用于回答“我语音里问过什么/语音买的什么”这类跨通道问题。";
    }

    @Override
    public JSONObject parametersSchema() {
        return ToolSchemas.object(new JSONObject());
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(JSONObject arguments, AgentContext context) {
        Integer userId = context.getUserId();
        if (userId == null) {
            return "当前任务未选择用户，无法读取语音记录。";
        }
        Result<Map<String, Object>> r = voiceClient.userSummary(userId.longValue(), 5);
        Map<String, Object> data = (r == null || !"200".equals(r.getCode())) ? null : r.getData();
        if (data == null) {
            return "语音服务暂不可用，暂时读不到语音记录。";
        }
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) data.getOrDefault("sessions", List.of());
        List<Map<String, Object>> orders = (List<Map<String, Object>>) data.getOrDefault("orders", List.of());
        if (sessions.isEmpty() && orders.isEmpty()) {
            return "该用户暂无语音导购记录（没在语音通道咨询或下单过）。";
        }

        StringBuilder sb = new StringBuilder();
        if (!sessions.isEmpty()) {
            sb.append("语音导购会话（最近 ").append(sessions.size()).append(" 次）：\n");
            int i = 1;
            for (Map<String, Object> s : sessions) {
                sb.append(i++).append(". ").append(s.get("started_at"))
                        .append("｜用户说：").append(abbrev(s.get("first_ask"), 60))
                        .append("｜AI 最后回复：").append(abbrev(s.get("last_reply"), 60))
                        .append("｜轮数：").append(s.get("turns"))
                        .append("｜结果：").append(orderOutcome(s)).append("\n");
            }
        }
        if (!orders.isEmpty()) {
            sb.append("语音下单：\n");
            for (Map<String, Object> o : orders) {
                sb.append("- ").append(o.get("order_no")).append(" ").append(o.get("product_name"))
                        .append(" ¥").append(o.get("total_amount"))
                        .append(" 状态：").append(statusCn(String.valueOf(o.get("status"))))
                        .append("（").append(o.get("created_at")).append("）\n");
            }
        }
        return sb.toString().trim();
    }

    private static String orderOutcome(Map<String, Object> s) {
        Object n = s.get("order_count");
        int count = n instanceof Number num ? num.intValue() : 0;
        return count > 0 ? "本次语音成交 " + count + " 单" : "未下单";
    }

    private static String abbrev(Object text, int max) {
        if (text == null) return "-";
        String t = String.valueOf(text).replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String statusCn(String status) {
        return switch (status) {
            case "SUCCEEDED", "PAID" -> "已成交";
            case "DISPATCHED" -> "已派发";
            case "FAILED" -> "失败";
            default -> status;
        };
    }
}
