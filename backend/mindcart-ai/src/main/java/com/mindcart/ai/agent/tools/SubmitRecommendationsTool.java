package com.mindcart.ai.agent.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.mindcart.ai.agent.AgentContext;
import com.mindcart.ai.agent.AgentTool;
import org.springframework.stereotype.Component;

/**
 * 提交最终推荐（终结工具）：只做参数解析与收集，落地校验由执行器完成 ——
 * 模型提交的商品仍要用真实工具数据复核价格库存，防止推荐买不到的商品。
 */
@Component
public class SubmitRecommendationsTool implements AgentTool {

    /** 模型可提交的推荐条数上限 */
    public static final int MAX_RECOMMENDATIONS = 3;

    @Override
    public String name() {
        return "submit_recommendations";
    }

    @Override
    public String label() {
        return "提交推荐结果";
    }

    @Override
    public String description() {
        return "提交最终推荐结果";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject itemProps = new JSONObject();
        itemProps.set("productId", ToolSchemas.prop("integer", "推荐商品ID"));
        itemProps.set("reason", ToolSchemas.prop("string", "推荐理由，基于真实数据"));
        JSONObject itemSchema = new JSONObject();
        itemSchema.set("type", "object");
        itemSchema.set("properties", itemProps);
        itemSchema.set("required", ToolSchemas.required("productId", "reason"));

        JSONObject itemsArray = new JSONObject();
        itemsArray.set("type", "array");
        itemsArray.set("items", itemSchema);
        itemsArray.set("description", "推荐商品列表，最多 " + MAX_RECOMMENDATIONS + " 个");

        JSONObject props = new JSONObject();
        props.set("items", itemsArray);
        return ToolSchemas.object(props, "items");
    }

    @Override
    public boolean isTerminal() {
        return true;
    }

    @Override
    public String execute(JSONObject arguments, AgentContext context) {
        // 空实现：执行器解析 items 后统一落地，不在这里做（见 AgentExecutor）
        return "ok";
    }

    /** 解析 items 参数 */
    public static JSONArray parseItems(JSONObject arguments) {
        return arguments == null ? null : arguments.getJSONArray("items");
    }
}
