package com.smartore.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 认证与授权规则（yml 配置驱动，集中可审计）。
 * 规则写法："GET /a/**" 限定方法；"/a/**" 匹配任意方法。
 */
@Component
@ConfigurationProperties(prefix = "smartore.auth")
public class AuthProperties {

    /** HMAC 签名密钥（与 user 服务共享，经环境变量注入） */
    private String jwtSecret;

    /** 免认证公开路径 */
    private List<String> publicPaths = new ArrayList<>();

    /** 管理员专属规则（命中要求 ADMIN 角色） */
    private List<String> adminRules = new ArrayList<>();

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public List<String> getAdminRules() {
        return adminRules;
    }

    public void setAdminRules(List<String> adminRules) {
        this.adminRules = adminRules;
    }
}
