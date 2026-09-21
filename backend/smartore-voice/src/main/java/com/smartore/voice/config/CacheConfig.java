package com.smartore.voice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存装配：显式 RedisCacheManager（替代 spring.cache.redis.* 自动配置），三件防：
 * - 防雪崩：JitterTtlRedisCacheManager 给所有 TTL 加 ±20% 一次性抖动，摊开失效时刻；
 * - 防穿透：默认允许缓存 null 值（未知 userId 的画像查询不再每次回源 DB）；
 * - 防击穿：热点回源走 @Cacheable(sync = true)（见 EmbeddingService，单 JVM 单飞）。
 * 外加 CacheErrorHandler：Redis 故障时读写异常不抛给业务，读失败回源、写失败丢弃
 * （缓存是加速层，不该成为单点故障源）。
 * <p>
 * 分区 TTL：embed 7 天（向量内容地址化，几乎不可变）、userProfileSnapshot 10 分钟
 * （画像会被行为信号更新，短 TTL 降低不一致窗口；null 值也只缓存这个量级）、默认 30 分钟。
 */
@Configuration
@Slf4j
public class CacheConfig implements CachingConfigurer {

    private final MeterRegistry meterRegistry;

    public CacheConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisSerializer<Object> json = newCacheValueSerializer();

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .prefixCacheNameWith("vs:cache:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(json));

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("embed", defaultConfig.entryTtl(Duration.ofDays(7)));
        cacheConfigs.put("userProfileSnapshot", defaultConfig.entryTtl(Duration.ofMinutes(10)));

        // 名义 TTL 单独传给抖动管理器（RedisCacheConfiguration 不暴露 getter）
        Map<String, Duration> nominalTtls = new HashMap<>();
        nominalTtls.put("embed", Duration.ofDays(7));
        nominalTtls.put("userProfileSnapshot", Duration.ofMinutes(10));

        return new JitterTtlRedisCacheManager(
                RedisCacheWriter.nonLockingRedisCacheWriter(factory),
                defaultConfig,
                cacheConfigs,
                nominalTtls,
                Duration.ofMinutes(30));
    }

    /**
     * 缓存值序列化器（与 CacheSerializationTest 共享同一构造，锁步回归）。
     * 用 JDK 序列化而不是 Jackson——这是测试裁决的结论，五条路全部验证过：
     * ① 直接传应用 mapper：无 default typing，读回 LinkedHashMap/ArrayList；
     * ② 自建 mapper + NON_FINAL：record/float[] 是 final 类型，不写类型信息；
     * ③ DefaultTyping.EVERYTHING：Jackson 3 已删除该常量；
     * ④ 无参构造：Spring Data Redis 4 不存在；
     * ⑤ builder().build()：round-trip 依然失败。
     * 而 JDK 序列化本就覆盖 final 类型，也正是 Boot 自动配置的默认、
     * UserProfileSnapshot 专门 implements Serializable 的原因；
     * 与升级前存量缓存数据（同为 JDK 序列化）完全兼容。
     */
    static RedisSerializer<Object> newCacheValueSerializer() {
        return new JdkSerializationRedisSerializer();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, org.springframework.cache.Cache cache, Object key) {
                record("get", cache.getName(), e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, org.springframework.cache.Cache cache, Object key, Object value) {
                record("put", cache.getName(), e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, org.springframework.cache.Cache cache, Object key) {
                record("evict", cache.getName(), e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, org.springframework.cache.Cache cache) {
                record("clear", cache.getName(), e);
            }

            private void record(String op, String cacheName, RuntimeException e) {
                Counter.builder("cache.redis.error")
                        .tag("op", op).tag("cache", cacheName)
                        .register(meterRegistry)
                        .increment();
                log.warn("Redis 缓存操作失败，降级回源/丢弃: cache={}, op={}, cause={}",
                        cacheName, op, e.toString());
            }
        };
    }
}
