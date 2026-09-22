package com.smartore.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要留痕的管理端写操作。
 *
 * 用法：加在 controller 方法上（不是 service 上）——审计要记录的是"谁、从哪个入口、
 * 做了什么"，这些信息只有 controller 层才有（IP、UA、原始入参）。
 *
 * 只记管理端的高价值写操作（改价、改库存、上下架、审核、改模型 Key/提示词/知识库）。
 * 不要给读接口和高频用户行为加：留痕的价值在于可追溯，不在于堆量。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {

    /** 业务模块，如"商品管理"、"模型配置" */
    String module();

    /** 动作，如"新增商品"、"审核评价" */
    String action();

    /**
     * 是否记录入参。默认记录（截断后），
     * 涉及敏感字段（密码、API Key）的接口应显式置 false。
     */
    boolean recordArgs() default true;
}
