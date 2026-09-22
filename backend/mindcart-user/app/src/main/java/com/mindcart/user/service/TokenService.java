package com.mindcart.user.service;

import cn.hutool.core.util.StrUtil;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.ResultCodeEnum;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * JWT 签发。签名密钥独立配置（不再用用户密码当密钥），网关用同一密钥校验。
 * 改密码不再使全网 token 失效，换来密钥不落库、可轮换。
 */
@Component
public class TokenService {

    @Value("${mindcart.jwt.secret:}")
    private String secret;

    @Value("${mindcart.jwt.ttl-hours:72}")
    private long ttlHours;

    @PostConstruct
    public void checkSecret() {
        if (StrUtil.isBlank(secret) || secret.length() < 32) {
            throw new IllegalStateException("mindcart.jwt.secret 必须配置且至少 32 字符（runtime.env 生成后注入）");
        }
    }

    public String create(Integer userId, String role) {
        return JWT.create()
                .withClaim("uid", userId)
                .withClaim("role", role)
                .withExpiresAt(new Date(System.currentTimeMillis() + ttlHours * 3600_000L))
                .sign(Algorithm.HMAC256(secret));
    }
}
