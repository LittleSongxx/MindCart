package com.mindcart.voice.controller;

import com.mindcart.voice.dto.IntentResult;
import com.mindcart.voice.service.IntentService;
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