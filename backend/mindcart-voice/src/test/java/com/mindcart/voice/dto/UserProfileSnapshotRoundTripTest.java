package com.mindcart.voice.dto;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 画像快照经 @Cacheable 写 Redis 走 JDK 序列化——必须可序列化且往返无损。
 * 回归背景：曾因未实现 Serializable 导致推荐链路整体 500。
 */
class UserProfileSnapshotRoundTripTest {

    @Test
    // 实现Serializable
    void case06() {
        assertTrue(java.io.Serializable.class.isAssignableFrom(UserProfileSnapshot.class));
    }

    @Test
    // ObjectOutputStream往返无损
    void case07() throws Exception {
        UserProfileSnapshot in = new UserProfileSnapshot(
                1L, "female", 25, 168, 55, "normal", "mid",
                Map.of("跑鞋", 0.88, "T恤", 0.42),
                Map.of("Nike", 0.75),
                List.of(1L, 2L, 3L),
                List.of(1L),
                new BigDecimal("0.60"),
                new BigDecimal("520.00"));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(in);
        }
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bos.toByteArray()))) {
            UserProfileSnapshot out = (UserProfileSnapshot) ois.readObject();
            assertEquals(in, out);                       // record 的 equals 逐分量比较
            assertEquals(0, out.priceSensitivity().compareTo(new BigDecimal("0.60")));
            assertEquals(Map.of("Nike", 0.75), out.brandAffinity());
        }
    }
}
