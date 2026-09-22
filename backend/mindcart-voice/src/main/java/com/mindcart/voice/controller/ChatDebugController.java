package com.mindcart.voice.controller;

import com.mindcart.voice.dto.EmotionResult;
import com.mindcart.voice.entity.SessionStateEntity;
import com.mindcart.voice.service.OrchestratorService;
import com.mindcart.voice.service.SessionStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/voice/debug/chat")
@RequiredArgsConstructor
public class ChatDebugController {

    private final OrchestratorService orchestrator;
    private final SessionStateService stateService;

    @PostMapping
    public EmotionResult chat(@RequestParam String sessionId,
                              @RequestParam Long userId,
                              @RequestBody String utterance) {
        return orchestrator.handle(sessionId, userId, utterance);
    }

    /**
     * 带路由诊断的对话入口（评测用）：除话术外回传**矫正后**的生效意图与阶段。
     * 为什么需要它：/api/v1/agent/intent 是纯分类器口径，绕过 Orchestrator 的意图矫正层
     * （reviseIntentByContext），两者可以不同——系统级评测必须能观测真正路由到哪条分支。
     */
    @PostMapping("/debug")
    public Map<String, Object> chatDebug(@RequestParam String sessionId,
                                         @RequestParam Long userId,
                                         @RequestBody String utterance) {
        EmotionResult result = orchestrator.handle(sessionId, userId, utterance);
        SessionStateEntity state = stateService.load(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("speechText", result.speechText());
        out.put("displayBlocks", result.displayBlocks());
        // 前端代执行动作帧（下单/取消）：订单类评测与联调靠它断言"该派发时派发、该拦时拦"
        out.put("actionFrame", result.actionFrame());
        out.put("intent", state.getCurrentIntent());
        out.put("phase", state.getPhase());
        out.put("slots", state.getSlots());
        return out;
    }
}
