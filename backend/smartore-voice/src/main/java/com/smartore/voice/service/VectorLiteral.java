package com.smartore.voice.service;

/**
 * float[] -> pgvector 文本字面量（"[0.1,0.2,...]"）。
 * <p>
 * 用字面量 + SQL 端 ?::vector 传参，而不是 pgvector-java 的 PGvector 类型映射：
 * addVectorType 只对调用它的那个池化连接生效，HikariCP 其它连接会随机报
 * "type vector does not exist"；字面量方案对任何连接都成立。
 */
public final class VectorLiteral {

    private VectorLiteral() {
    }

    public static String of(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 10).append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
