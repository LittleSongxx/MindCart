package com.mindcart.voice.controller;

import com.mindcart.common.result.Result;
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
 * 语音会话运营面（管理端）：会话列表 / 会话详情（对话流水 + 归因订单）/ 汇总指标。
 * <p>
 * 走网关 /voice/admin/** → 命中 admin-rules 要求 ADMIN 角色；
 * 服务侧另有 voice-shopping.debug.enabled 闸门（生产置 false 即 404）。
 * 只读，不改任何状态。
 */
@RestController
@RequestMapping("/voice/admin")
@RequiredArgsConstructor
public class VoiceAdminController {

    private final JdbcTemplate jdbc;

    /** 会话列表（分页）：带消息数/成交订单数/成交额，按开始时间倒序 */
    @GetMapping("/sessions")
    public Result<Map<String, Object>> sessions(@RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "20") Integer size,
                                                @RequestParam(required = false) Long userId,
                                                @RequestParam(required = false) String outcome) {
        int limit = Math.max(1, Math.min(size == null ? 20 : size, 100));
        int offset = (Math.max(1, page == null ? 1 : page) - 1) * limit;

        // 位置参数 + 显式 CAST：PgJDBC 对 ? IS NULL 的参数类型推断需要明确类型
        String where = " WHERE (CAST(? AS BIGINT) IS NULL OR s.user_id = CAST(? AS BIGINT)) "
                + "AND (CAST(? AS TEXT) IS NULL OR s.outcome = CAST(? AS TEXT)) ";
        Object[] filter = {userId, userId,
                outcome == null || outcome.isBlank() ? null : outcome,
                outcome == null || outcome.isBlank() ? null : outcome};

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM session s" + where, Integer.class, filter);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id AS session_id, s.user_id, s.channel, s.outcome, "
                        + "s.started_at, s.ended_at, s.last_active_at, "
                        + "(SELECT phase FROM session_state st WHERE st.session_id = s.id) AS phase, "
                        + "(SELECT COUNT(*) FROM session_message m WHERE m.session_id = s.id) AS msg_count, "
                        + "(SELECT COUNT(*) FROM voice_order_event o WHERE o.session_id = s.id AND o.status = 'SUCCEEDED') AS order_count, "
                        + "(SELECT COALESCE(SUM(o.total_amount), 0) FROM voice_order_event o WHERE o.session_id = s.id AND o.status = 'SUCCEEDED') AS order_amount "
                        + "FROM session s" + where
                        + "ORDER BY s.started_at DESC LIMIT " + limit + " OFFSET " + offset,
                filter);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("list", rows);
        return Result.success(out);
    }

    /** 会话详情：对话流水 + 归因订单（一条会长这样：说了什么 → 下了哪单） */
    @GetMapping("/sessions/{sessionId}")
    public Result<Map<String, Object>> sessionDetail(@PathVariable String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", jdbc.queryForList(
                "SELECT turn, role, agent_name, intent, content_text, latency_ms, created_at "
                        + "FROM session_message WHERE session_id = ? ORDER BY turn ASC, id ASC", sessionId));
        out.put("orders", jdbc.queryForList(
                "SELECT id, action, request_id, order_no, product_id, product_name, quantity, "
                        + "total_amount, status, fail_reason, created_at "
                        + "FROM voice_order_event WHERE session_id = ? ORDER BY id ASC", sessionId));
        out.put("state", jdbc.queryForList(
                // jsonb / bigint[] 必须显式转 text：JDBC 返回 PgArray/PGobject，Jackson 无法序列化
                "SELECT phase, current_intent, slots::text AS slots, "
                        + "last_recommendations::text AS last_recommendations, updated_at "
                        + "FROM session_state WHERE session_id = ?", sessionId));
        return Result.success(out);
    }

    /** 归因订单流水（跨会话，按时间倒序）：语音贡献的直接证据 */
    @GetMapping("/orders")
    public Result<List<Map<String, Object>>> orders(@RequestParam(defaultValue = "50") Integer limit) {
        return Result.success(jdbc.queryForList(
                "SELECT id, session_id, user_id, action, request_id, order_no, product_name, "
                        + "quantity, total_amount, status, fail_reason, created_at "
                        + "FROM voice_order_event ORDER BY id DESC LIMIT ?",
                Math.max(1, Math.min(limit == null ? 50 : limit, 200))));
    }

    /** 运营汇总：会话数 / 成交会话 / 语音成交额 / 转化率 / 消息量 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", jdbc.queryForObject("SELECT COUNT(*) FROM session", Integer.class));
        out.put("orderedSessions", jdbc.queryForObject(
                "SELECT COUNT(*) FROM session WHERE outcome = 'ORDERED'", Integer.class));
        out.put("messages", jdbc.queryForObject("SELECT COUNT(*) FROM session_message", Integer.class));
        out.put("dispatched", jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_order_event WHERE action='CREATE'", Integer.class));
        out.put("succeeded", jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_order_event WHERE status='SUCCEEDED' AND action='CREATE'", Integer.class));
        out.put("failed", jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_order_event WHERE status='FAILED' AND action='CREATE'", Integer.class));
        out.put("gmv", jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_amount),0) FROM voice_order_event WHERE status='SUCCEEDED' AND action='CREATE'",
                java.math.BigDecimal.class));
        out.put("last24hSessions", jdbc.queryForObject(
                "SELECT COUNT(*) FROM session WHERE started_at > NOW() - INTERVAL '24 hours'", Integer.class));
        return Result.success(out);
    }
}
