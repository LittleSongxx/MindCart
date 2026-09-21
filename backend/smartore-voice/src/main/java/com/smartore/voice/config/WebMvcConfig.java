package com.smartore.voice.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 调试/管理端点闸门。
 * <p>
 * HTTP 入口身份校验由 smartore-common 的 UserContextFilter 统一承担
 * （X-Gateway-Token / X-Internal-Token 双凭证）；WS 语音入口由
 * AuthHandshakeInterceptor 单独校验。本类只保留调试端点闸门：
 * {@code voice-shopping.debug.enabled=false} 时 /voice/debug/** 与
 * /voice/admin/** 直接 404（网关侧该前缀另有 ADMIN RBAC 规则双保险）。
 */
@Configuration
@Slf4j
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${voice-shopping.debug.enabled:false}")
    private boolean debugEnabled;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!debugEnabled) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request,
                                         HttpServletResponse response, Object handler) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return false;
                }
            }).addPathPatterns(
                    "/voice/debug/**",
                    "/voice/admin/**"
            );
        } else {
            log.warn(">>> 语音服务调试/管理端点已开启（voice-shopping.debug.enabled=true），仅用于开发环境 <<<");
        }
    }
}
