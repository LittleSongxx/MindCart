package com.smartore.voice.config;

import com.smartore.voice.dto.UserProfileSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存值序列化 round-trip 验证：若序列化器写出的值读不回原类型
 * （Jackson 系对 final 的 record/float[] 会退化成 LinkedHashMap/ArrayList），
 * @Cacheable 强转即炸（或经 CacheErrorHandler 退化为永久 miss）。
 * 这个测试是"缓存必须能读回写入值"的底线回归。
 */
class CacheSerializationTest {

    /** 与 CacheConfig 同一构造，保证测试锁定的就是线上用的序列化器 */
    private final org.springframework.data.redis.serializer.RedisSerializer<Object> ser =
            CacheConfig.newCacheValueSerializer();

    @Test
    void float_array_round_trips_with_type() {
        float[] original = {1.5f, -2.25f, 0.0f};

        Object restored = ser.deserialize(ser.serialize(original));

        assertThat(restored).as("float[] 读回必须是 float[] 而不是 List<Double>: 实际=%s",
                restored == null ? "null" : restored.getClass().getName())
                .isInstanceOf(float[].class);
        assertThat((float[]) restored).containsExactly(1.5f, -2.25f, 0.0f);
    }

    @Test
    void record_round_trips_with_type() {
        UserProfileSnapshot original = snapshot();

        Object restored = ser.deserialize(ser.serialize(original));

        assertThat(restored).as("record 读回必须是原类型而不是 LinkedHashMap: 实际=%s",
                restored == null ? "null" : restored.getClass().getName())
                .isInstanceOf(UserProfileSnapshot.class);
    }

    private static UserProfileSnapshot snapshot() {
        try {
            // UserProfileSnapshot 是 record：取第一个全参构造做反射构造，避免随 DTO 演进改测试
            var ctor = UserProfileSnapshot.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                Class<?> t = ctor.getParameterTypes()[i];
                args[i] = t == String.class ? "x" : t == int.class || t == Integer.class ? 1 : null;
            }
            return (UserProfileSnapshot) ctor.newInstance(args);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
