package com.mindcart.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存配置。刻意做成显式开关（enabled 默认 false）：
 * 缓存是每个服务自觉引入的加速层，不该因为"类路径上恰好有 Redis"就被隐式打开——
 * ai 服务只用 StringRedisTemplate 做分布式锁，它拿到一个 CacheManager 属于意外行为。
 */
@ConfigurationProperties(prefix = "mindcart.cache")
public class CacheProperties {

    /** 是否启用 Spring Cache + Redis（默认关闭，服务需在 yml 中显式打开） */
    private boolean enabled = false;

    /** 缓存 key 前缀，按服务区分（如 goods:cache:） */
    private String keyPrefix = "mindcart:cache:";

    /** 未单独指定 TTL 的缓存区使用该值 */
    private Duration defaultTtl = Duration.ofMinutes(30);

    /** 分区 TTL：键为缓存区名（与 @Cacheable(value=...) 一致），值为 TTL */
    private Map<String, Duration> ttls = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public Map<String, Duration> getTtls() {
        return ttls;
    }

    public void setTtls(Map<String, Duration> ttls) {
        this.ttls = ttls;
    }
}
