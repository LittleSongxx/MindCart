package com.smartore.common.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
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
 * 共享缓存装配（各服务通过 {@code smartore.cache.enabled=true} 显式开启）。
 *
 * 三件防 + 一条底线：
 * - 防雪崩：TTL 统一加 ±20% 抖动（{@link JitterTtlRedisCacheManager}），摊开失效时刻；
 * - 防穿透：默认允许缓存 null 值（查不到的商品/分类不再每次都回源 DB），
 *   只对短 TTL 的缓存区生效，避免空值长期占位挡住后来的真实数据；
 * - 防击穿：热点回源由调用方用 {@code @Cacheable(sync = true)} 单飞（单 JVM 维度）；
 * - 底线：{@link CacheErrorHandler} 吞掉 Redis 读写异常——读失败回源、写失败丢弃，
 *   缓存是加速层，不允许变成业务单点故障。
 *
 * 值序列化用 JDK 序列化（与 Boot 自动配置默认一致）：Jackson 路线在 final 类型
 * （record/float[]）上会退化成 LinkedHashMap/ArrayList，CacheConfig 的注释里有完整验证记录。
 * 代价是被缓存的实体必须 implements Serializable。
 */
@AutoConfiguration
@ConditionalOnClass({RedisConnectionFactory.class, RedisCacheManager.class})
@ConditionalOnProperty(prefix = "smartore.cache", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CacheProperties.class)
@EnableCaching
public class CacheAutoConfiguration implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheAutoConfiguration.class);

    private final CacheProperties properties;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public CacheAutoConfiguration(CacheProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Bean
    @ConditionalOnMissingBean(RedisCacheManager.class)
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.getDefaultTtl())
                .prefixCacheNameWith(properties.getKeyPrefix())
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(newCacheValueSerializer()));

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        properties.getTtls().forEach((name, ttl) -> cacheConfigs.put(name, defaultConfig.entryTtl(ttl)));

        log.info("共享缓存已启用：prefix={}, 分区TTL={}, 默认TTL={}",
                properties.getKeyPrefix(), properties.getTtls(), properties.getDefaultTtl());

        return new JitterTtlRedisCacheManager(
                RedisCacheWriter.nonLockingRedisCacheWriter(factory),
                defaultConfig,
                cacheConfigs,
                properties.getTtls(),
                properties.getDefaultTtl());
    }

    /**
     * 缓存值序列化器（与 voice 的 CacheSerializationTest 口径一致，锁步回归）。
     * 用 JDK 序列化而不是 Jackson——这是测试裁决的结论，五条路全部验证过：
     * ① 直接传应用 mapper：无 default typing，读回 LinkedHashMap/ArrayList；
     * ② 自建 mapper + NON_FINAL：record/float[] 是 final 类型，不写类型信息；
     * ③ DefaultTyping.EVERYTHING：Jackson 3 已删除该常量；
     * ④ 无参构造：Spring Data Redis 4 不存在；
     * ⑤ builder().build()：round-trip 依然失败。
     */
    public static RedisSerializer<Object> newCacheValueSerializer() {
        return new JdkSerializationRedisSerializer();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                record("get", cache.getName(), e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                record("put", cache.getName(), e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                record("evict", cache.getName(), e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                record("clear", cache.getName(), e);
            }

            private void record(String op, String cacheName, RuntimeException e) {
                MeterRegistry registry = meterRegistry.getIfAvailable();
                if (registry != null) {
                    Counter.builder("cache.redis.error")
                            .tag("op", op).tag("cache", cacheName)
                            .register(registry)
                            .increment();
                }
                log.warn("Redis 缓存操作失败，降级回源/丢弃: cache={}, op={}, cause={}",
                        cacheName, op, e.toString());
            }
        };
    }

    /** 供服务侧在 yml 未配置时也能引用默认 TTL 口径 */
    public static Duration defaultTtl() {
        return Duration.ofMinutes(30);
    }
}
