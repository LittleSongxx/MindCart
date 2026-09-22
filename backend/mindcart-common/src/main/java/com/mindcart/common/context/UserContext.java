package com.mindcart.common.context;

import com.mindcart.common.constant.HeaderNames;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.ResultCodeEnum;

/**
 * 当前请求的用户上下文（由网关解析 JWT 后经信任头注入，UserContextFilter 装载）。
 * 业务代码不再自行解析 token，鉴权是网关的单一职责。
 */
public final class UserContext {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private final Integer userId;
    private final String role;

    private UserContext(Integer userId, String role) {
        this.userId = userId;
        this.role = role;
    }

    public static void set(Integer userId, String role) {
        HOLDER.set(new UserContext(userId, role));
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 当前登录用户 ID；未登录返回 null（公开接口场景） */
    public static Integer getUserIdOrNull() {
        UserContext context = HOLDER.get();
        return context == null ? null : context.userId;
    }

    /** 当前登录用户 ID；未登录直接抛 401（绝大多数业务接口用这个） */
    public static Integer requireUserId() {
        Integer userId = getUserIdOrNull();
        if (userId == null) {
            throw new CustomException(ResultCodeEnum.UNAUTHORIZED);
        }
        return userId;
    }

    public static String getRoleOrNull() {
        UserContext context = HOLDER.get();
        return context == null ? null : context.role;
    }

    public static boolean isAdmin() {
        return HeaderNames.ROLE_ADMIN.equals(getRoleOrNull());
    }
}
