package com.mindcart.ai.agent;

import cn.hutool.json.JSONObject;

/**
 * 导购 Agent 工具契约（策略模式）。
 *
 * 单体版是一整个 switch-case 分发；微服务版把每个工具做成独立策略 Bean：
 * - 注册即生效（Spring 收集所有 AgentTool 实现），新增工具零改动分发逻辑（OCP）；
 * - 工具定义（schema）与执行（execute）内聚在同一个类里，数据来自哪个服务一目了然；
 * - 单测可以独立 mock 每个工具。
 */
public interface AgentTool {

    /** 工具名（Function Calling 的 function.name） */
    String name();

    /** 中文步骤名（AgentStep 展示用） */
    String label();

    /** 工具描述（交给模型的自然语言说明） */
    String description();

    /** 参数 JSON Schema（OpenAI tools 格式的 parameters 对象） */
    JSONObject parametersSchema();

    /** 执行工具，返回给模型的文本结果 */
    String execute(JSONObject arguments, AgentContext context);

    /** 是否为"提交最终结果"类工具（执行器据此结束循环） */
    default boolean isTerminal() {
        return false;
    }
}
