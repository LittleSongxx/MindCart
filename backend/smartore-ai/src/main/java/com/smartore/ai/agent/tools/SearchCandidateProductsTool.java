package com.smartore.ai.agent.tools;

import cn.hutool.json.JSONObject;
import com.smartore.ai.agent.AgentContext;
import com.smartore.ai.agent.AgentTool;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.Result;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** 检索候选商品：关键词 + 预算上限 → 在售候选列表（数据来自商品服务） */
@Component
public class SearchCandidateProductsTool implements AgentTool {

    @Resource
    private GoodsFeignClient goodsClient;

    @Override
    public String name() {
        return "search_candidate_products";
    }

    @Override
    public String label() {
        return "检索候选商品";
    }

    @Override
    public String description() {
        return "按关键词和预算上限检索上架商品，返回候选商品列表（含id、名称、价格、标签、卖点）";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject props = new JSONObject();
        props.set("keyword", ToolSchemas.prop("string", "商品关键词，可为空"));
        props.set("maxPrice", ToolSchemas.prop("number", "价格上限，可为空"));
        return ToolSchemas.object(props);
    }

    @Override
    public String execute(JSONObject arguments, AgentContext context) {
        String keyword = arguments.getStr("keyword");
        BigDecimal maxPrice = arguments.getBigDecimal("maxPrice");
        List<ProductVO> products = unwrap(goodsClient.searchOnSale(
                keyword == null || keyword.isBlank() ? null : keyword, maxPrice, 8));
        if (products.isEmpty()) {
            return "未检索到符合条件的上架商品。";
        }
        cn.hutool.json.JSONArray array = new cn.hutool.json.JSONArray();
        for (ProductVO product : products) {
            JSONObject item = new JSONObject();
            item.set("id", product.getId());
            item.set("name", product.getName());
            item.set("price", product.getPrice());
            item.set("originalPrice", product.getOriginalPrice());
            item.set("tags", product.getTags());
            item.set("sellingPoint", product.getSellingPoint());
            item.set("stockQuantity", product.getStockQuantity());
            array.add(item);
        }
        return array.toString();
    }

    static <T> T unwrap(Result<T> result) {
        if (result == null || !ResultCodeEnum.SUCCESS.getCode().equals(result.getCode())) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR,
                    result == null ? "商品服务不可用" : result.getMsg());
        }
        return result.getData();
    }
}
