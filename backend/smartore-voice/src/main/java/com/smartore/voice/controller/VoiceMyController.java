package com.smartore.voice.controller;

import com.smartore.common.context.UserContext;
import com.smartore.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端"我的语音记录"：登录用户查看自己的语音导购会话与归因订单。
 * <p>
 * 身份只取网关注入的 X-User-Id（UserContext），**不接受客户端自报 userId**——
 * 查别人的会话会拿到 403。只读，不返回 token/内部字段。
 */
@RestController
@RequestMapping("/voice/my")
@RequiredArgsConstructor
public class VoiceMyController {

    private final JdbcTemplate jdbc;

    /** 我的语音会话列表（时间倒序）：轮数、是否成交、成交额、开场需求 */
    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> mySessions(@RequestParam(defaultValue = "20") Integer limit) {
        Long userId = UserContext.requireUserId().longValue();
        return Result.success(jdbc.queryForList(
                "SELECT s.id AS session_id, s.channel, s.outcome, s.started_at, s.ended_at, s.last_active_at, "
                        + "(SELECT COUNT(*) FROM session_message m WHERE m.session_id = s.id AND m.role = 'USER') AS turns, "
                        + "(SELECT COUNT(*) FROM voice_order_event o WHERE o.session_id = s.id AND o.status = 'SUCCEEDED') AS order_count, "
                        + "(SELECT COALESCE(SUM(o.total_amount), 0) FROM voice_order_event o WHERE o.session_id = s.id AND o.status = 'SUCCEEDED') AS order_amount, "
                        + "(SELECT content_text FROM session_message m WHERE m.session_id = s.id AND m.role = 'USER' ORDER BY m.turn ASC LIMIT 1) AS first_ask, "
                        + "(SELECT content_text FROM session_message m WHERE m.session_id = s.id AND m.role = 'ASSISTANT' ORDER BY m.turn DESC LIMIT 1) AS last_reply "
                        + "FROM session s WHERE s.user_id = ? AND EXISTS (SELECT 1 FROM session_message m WHERE m.session_id = s.id) ORDER BY s.started_at DESC LIMIT ?",
                userId, Math.max(1, Math.min(limit == null ? 20 : limit, 50))));
    }

    /** 我的某次语音会话详情：对话流水 + 归因订单（归属校验：非本人返回 403） */
    @GetMapping("/sessions/{sessionId}")
    public Result<Map<String, Object>> mySessionDetail(@PathVariable String sessionId) {
        Long userId = UserContext.requireUserId().longValue();
        Long owner = jdbc.query("SELECT user_id FROM session WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, sessionId);
        if (owner == null) {
            throw new com.smartore.common.exception.CustomException(
                    com.smartore.common.result.ResultCodeEnum.PARAM_ERROR, "会话不存在");
        }
        if (!owner.equals(userId)) {
            // 越权访问：common 的全局异常处理器会映射为 HTTP 403
            throw new com.smartore.common.exception.CustomException(
                    com.smartore.common.result.ResultCodeEnum.FORBIDDEN);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", jdbc.queryForList(
                "SELECT turn, role, agent_name, intent, content_text, created_at "
                        + "FROM session_message WHERE session_id = ? AND role <> 'SYSTEM' "
                        + "ORDER BY turn ASC, id ASC", sessionId));
        out.put("orders", jdbc.queryForList(
                "SELECT action, order_no, product_name, quantity, total_amount, status, created_at "
                        + "FROM voice_order_event WHERE session_id = ? ORDER BY id ASC", sessionId));
        return Result.success(out);
    }
}
