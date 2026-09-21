package com.smartore.voice.controller;

import com.smartore.voice.dto.IntentResult;
import com.smartore.voice.service.IntentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/voice/debug/agent")
@RequiredArgsConstructor
public class IntentDebugController {

    private final IntentService intentService;

    @PostMapping("/intent")
    public IntentResult classify(@RequestParam String sessionId,
                                 @RequestBody String utterance) {
        return intentService.classify(sessionId, utterance);
    }
}