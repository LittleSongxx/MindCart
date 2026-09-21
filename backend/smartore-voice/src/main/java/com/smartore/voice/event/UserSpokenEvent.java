package com.smartore.voice.event;

public record UserSpokenEvent(String sessionId, Long userId, String utterance, long timestamp) {
}