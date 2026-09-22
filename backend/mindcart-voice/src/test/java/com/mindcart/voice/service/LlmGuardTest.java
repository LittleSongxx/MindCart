package com.mindcart.voice.service;

import com.mindcart.voice.repository.SessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * LlmGuard 弹性行为验证（不依赖 Sentinel 规则加载——无规则时 SphU.entry 全部放行，
 * 覆盖的是重试/降级/计数这些 Guard 自身逻辑）。
 */
class LlmGuardTest {

    private LlmGuard guard;

    @BeforeEach
    void setUp() {
        guard = new LlmGuard(new SimpleMeterRegistry(), mock(SessionRepository.class));
    }

    @Test
    void success_returns_value() {
        String out = guard.call(() -> "ok", () -> "fallback");
        assertThat(out).isEqualTo("ok");
    }

    @Test
    void non_retryable_failure_falls_back_without_retry() {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> boom = () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("content filter rejected");
        };

        String out = guard.call(boom, () -> "fallback");

        assertThat(out).isEqualTo("fallback");
        assertThat(attempts.get()).as("非网络异常不重试").isEqualTo(1);
    }

    @Test
    void network_failure_retries_once_then_succeeds() {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> flaky = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException(new IOException("connection reset"));
            }
            return "ok-after-retry";
        };

        String out = guard.call(flaky, () -> "fallback");

        assertThat(out).isEqualTo("ok-after-retry");
        assertThat(attempts.get()).as("网络类异常重试恰好一次").isEqualTo(2);
    }

    @Test
    void persistent_network_failure_falls_back_after_single_retry() {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> alwaysDown = () -> {
            attempts.incrementAndGet();
            throw new RuntimeException(new java.net.SocketTimeoutException("read timed out"));
        };

        String out = guard.call(alwaysDown, () -> "fallback");

        assertThat(out).isEqualTo("fallback");
        assertThat(attempts.get()).as("首次 + 重试一次 = 2 次，不无限重试").isEqualTo(2);
    }
}
