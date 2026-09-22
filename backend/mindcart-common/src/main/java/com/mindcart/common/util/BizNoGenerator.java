package com.mindcart.common.util;

import cn.hutool.core.date.DateUtil;

import java.security.SecureRandom;
import java.util.Date;

/**
 * 业务单号生成：前缀 + 毫秒时间戳 + 3 位随机数。
 * 单体版只用毫秒时间戳，并发下会碰撞（靠唯一索引兜底会直接报错）；
 * 加随机后缀后碰撞概率可忽略，唯一索引仍保留作为最后防线。
 */
public final class BizNoGenerator {

    private static final String ALPHABET = "0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private BizNoGenerator() {
    }

    public static String next(String prefix) {
        StringBuilder builder = new StringBuilder(prefix)
                .append(DateUtil.format(new Date(), "yyyyMMddHHmmssSSS"));
        for (int i = 0; i < 3; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
