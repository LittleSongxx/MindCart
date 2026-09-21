package com.smartore.voice.controller;

import com.smartore.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集群内只读端点：把语音域的会话与归因订单暴露给其它 AI 域（当前是 smartore-ai）。
 * <p>
 * 背景（统一视图）：语音与文本两条 AI 链路各自记账，文本侧此前完全看不到
 * "用户语音里问过什么、语音里下的单"。这里给 ai 一个受限的读取口，
 * 让文本导购 Agent 能回答跨通道的问题（如"我语音里买过什么"）。
 * 网关不路由 /internal/**，凭证为 X-Internal-Token（Feign 透传）。
 */
@RestController
@RequestMapping("/internal/voice")
@RequiredArgsConstructor
public class VoiceInternalController {

    private final JdbcTemplate jdbc;

    /** 用户语音侧摘要：最近会话（含开场需求/最后回复/是否成交）+ 归因订单 */
    @GetMapping("/user-summary/{userId}")
    public Result<Map<String, Object>> userSummary(@PathVariable Long userId,
                                                   @RequestParam(defaultValue = "5") Integer limit) {
        int n = Math.max(1, Math.min(limit == null ? 5 : limit, 20));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", jdbc.queryForList(
                "SELECT s.id AS session_id, s.channel, s.outcome, s.started_at, "
                        + "(SELECT content_text FROM session_message m WHERE m.session_id = s.id AND m.role = 'USER' ORDER BY m.turn ASC LIMIT 1) AS first_ask, "
                        + "(SELECT content_text FROM session_message m WHERE m.session_id = s.id AND m.role = 'ASSISTANT' ORDER BY m.turn DESC LIMIT 1) AS last_reply, "
                        + "(SELECT COUNT(*) FROM session_message m WHERE m.session_id = s.id AND m.role = 'USER') AS turns, "
                        + "(SELECT COUNT(*) FROM voice_order_event o WHERE o.session_id = s.id AND o.status = 'SUCCEEDED') AS order_count "
                        + "FROM session s WHERE s.user_id = ? ORDER BY s.started_at DESC LIMIT " + n, userId));
        out.put("orders", jdbc.queryForList(
                "SELECT order_no, product_name, quantity, total_amount, status, action, created_at "
                        + "FROM voice_order_event WHERE user_id = ? AND status = 'SUCCEEDED' ORDER BY id DESC LIMIT " + n, userId));
        return Result.success(out);
    }

    /** 指定会话的对话流水（AI 引用具体对话；不带 SYSTEM 行，避免噪声） */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<java.util.List<Map<String, Object>>> sessionMessages(@PathVariable String sessionId,
                                                                       @RequestParam(defaultValue = "10") Integer limit) {
        int n = Math.max(1, Math.min(limit == null ? 10 : limit, 50));
        return Result.success(jdbc.queryForList(
                "SELECT turn, role, intent, content_text, created_at FROM session_message "
                        + "WHERE session_id = ? AND role <> 'SYSTEM' ORDER BY turn ASC, id ASC LIMIT " + n, sessionId));
    }
}
