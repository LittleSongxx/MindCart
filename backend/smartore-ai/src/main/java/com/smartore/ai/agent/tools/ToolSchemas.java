package com.smartore.ai.agent.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/** Function Calling 工具 schema 构造助手 */
final class ToolSchemas {

    private ToolSchemas() {
    }

    static JSONObject prop(String type, String description) {
        JSONObject schema = new JSONObject();
        schema.set("type", type);
        schema.set("description", description);
        return schema;
    }

    static JSONObject object(JSONObject properties, String... required) {
        JSONObject parameters = new JSONObject();
        parameters.set("type", "object");
        parameters.set("properties", properties);
        parameters.set("required", JSONUtil.parseArray(required));
        return parameters;
    }

    static JSONArray required(String... required) {
        return JSONUtil.parseArray(required);
    }
}
