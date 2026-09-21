package com.smartore.voice.controller;

import com.smartore.voice.memory.ShortTermMemory;
import com.smartore.voice.service.LongTermMemoryWriter;
import com.smartore.voice.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/voice/debug/memory")
@RequiredArgsConstructor
public class MemoryDebugController {

    private final LongTermMemoryWriter writer;
    private final SessionService sessionService;
    private final ShortTermMemory memory;

    /** 多轮评测用：向会话短期记忆注入任意轮次（role/agent/text）。 */
    public record SeedTurn(String role, String agent, String text) {}

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam String sessionId,
                                    @RequestBody List<SeedTurn> turns) {
        memory.clear(sessionId);
        for (SeedTurn t : turns) {
            memory.append(sessionId, new ShortTermMemory.Turn(
                    t.role(), t.agent(), t.text(), System.currentTimeMillis()));
        }
        return Map.of("ok", true, "sessionId", sessionId, "turns", turns.size());
    }

    @PostMapping("/flush")
    public Map<String, Object> flush(@RequestParam String sessionId,
                                     @RequestParam(required = false) Long userId) {
        Long uid = userId != null ? userId : sessionService.findUserId(sessionId);
        if (uid == null) {
            return Map.of("ok", false, "reason", "无法解析 userId，请显式传 userId 参数");
        }
        writer.flushOnSessionEnd(sessionId, uid);
        return Map.of("ok", true, "sessionId", sessionId, "userId", uid);
    }
}