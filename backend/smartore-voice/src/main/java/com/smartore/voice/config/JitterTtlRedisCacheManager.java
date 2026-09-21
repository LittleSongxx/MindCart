package com.smartore.voice.config;

import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TTL 抖动缓存管理器：所有缓存区若用整齐划一的 TTL，会批量到点同时失效、
 * 集中回源打 DB/Embedding API（缓存雪崩）。这里在每个缓存区创建时对 TTL 做
 * 一次性 ±20% 随机（每 JVM 每缓存区固定一次，避免运行时漂移），把失效时刻摊开。
 * <p>
 * 注：RedisCacheConfiguration 不暴露 TTL getter，因此各缓存区的名义 TTL 由
 * 装配方（CacheConfig）随构造器显式传入，这里只负责加抖动。
 */
public class JitterTtlRedisCacheManager extends RedisCacheManager {

    private static final double JITTER_FACTOR = 0.2;

    private final Map<String, Duration> nominalTtls;
    private final Duration defaultTtl;
    /** 每缓存区固定一次抖动结果（创建即定格，重建同名缓存区沿用同一抖动值）。 */
    private final Map<String, Duration> jitteredTtls = new ConcurrentHashMap<>();

    public JitterTtlRedisCacheManager(RedisCacheWriter cacheWriter,
                                      RedisCacheConfiguration defaultCacheConfiguration,
                                      Map<String, RedisCacheConfiguration> initialCacheConfigurations,
                                      Map<String, Duration> nominalTtls,
                                      Duration defaultTtl) {
        super(cacheWriter, defaultCacheConfiguration, initialCacheConfigurations);
        this.nominalTtls = nominalTtls;
        this.defaultTtl = defaultTtl;
    }

    @Override
    protected RedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfig) {
        if (cacheConfig == null) {
            return super.createRedisCache(name, null);
        }
        Duration jittered = jitteredTtls.computeIfAbsent(name, n -> {
            Duration ttl = nominalTtls.getOrDefault(n, defaultTtl);
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                return ttl;
            }
            double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * JITTER_FACTOR;
            return Duration.ofMillis((long) (ttl.toMillis() * factor));
        });
        if (jittered == null) {
            return super.createRedisCache(name, cacheConfig);
        }
        return super.createRedisCache(name, cacheConfig.entryTtl(jittered));
    }
}
