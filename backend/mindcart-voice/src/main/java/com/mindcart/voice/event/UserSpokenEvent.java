package com.mindcart.voice.event;

public record UserSpokenEvent(String sessionId, Long userId, String utterance, long timestamp) {
}