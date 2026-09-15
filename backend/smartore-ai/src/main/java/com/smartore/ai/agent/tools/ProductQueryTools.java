package com.smartore.ai.agent.tools;

import cn.hutool.json.JSONObject;
import com.smartore.ai.agent.AgentContext;
import com.smartore.ai.agent.AgentTool;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 商品事实查询工具族：价格 / 库存 / 优惠。同一数据源（商品快照）三种视图，
 * 每次实时查询，保证推荐理由基于真实数据（防幻觉的证据链）。
 */
public final class ProductQueryTools {

    private ProductQueryTools() {
    }

    static ProductVO load(GoodsFeignClient goodsClient, JSONObject arguments) {
        Integer productId = arguments.getInt("productId");
        return SearchCandidateProductsTool.unwrap(goodsClient.getProduct(productId));
    }

    @Component
    public static class PriceTool implements AgentTool {

        @Resource
        private GoodsFeignClient goodsClient;

        @Override
        public String name() {
            return "query_product_price";
        }

        @Override
        public String label() {
            return "查询商品价格";
        }

        @Override
        public String description() {
            return "查询指定商品的真实价格和原价";
        }

        @Override
        public JSONObject parametersSchema() {
            JSONObject props = new JSONObject();
            props.set("productId", ToolSchemas.prop("integer", "商品ID"));
            return ToolSchemas.object(props, "productId");
        }

        @Override
        public String execute(JSONObject arguments, AgentContext context) {
            ProductVO product = load(goodsClient, arguments);
            return "商品：" + product.getName() + "，现价：" + product.getPrice() + "，原价：" + product.getOriginalPrice();
        }
    }

    @Component
    public static class StockTool implements AgentTool {

        @Resource
        private GoodsFeignClient goodsClient;

        @Override
        public String name() {
            return "query_product_stock";
        }

        @Override
        public String label() {
            return "查询商品库存";
        }

        @Override
        public String description() {
            return "查询指定商品的库存数量和是否可购买";
        }

        @Override
        public JSONObject parametersSchema() {
            JSONObject props = new JSONObject();
            props.set("productId", ToolSchemas.prop("integer", "商品ID"));
            return ToolSchemas.object(props, "productId");
        }

        @Override
        public String execute(JSONObject arguments, AgentContext context) {
            ProductVO product = load(goodsClient, arguments);
            int stock = product.getStockQuantity() == null ? 0 : product.getStockQuantity();
            return "商品：" + product.getName() + "，库存数量：" + stock + "，是否可买：" + (stock > 0 ? 1 : 0);
        }
    }

    @Component
    public static class PromotionTool implements AgentTool {

        @Resource
        private GoodsFeignClient goodsClient;

        @Override
        public String name() {
            return "query_product_promotion";
        }

        @Override
        public String label() {
            return "查询商品优惠";
        }

        @Override
        public String description() {
            return "查询指定商品的优惠金额和折扣率";
        }

        @Override
        public JSONObject parametersSchema() {
            JSONObject props = new JSONObject();
            props.set("productId", ToolSchemas.prop("integer", "商品ID"));
            return ToolSchemas.object(props, "productId");
        }

        @Override
        public String execute(JSONObject arguments, AgentContext context) {
            ProductVO product = load(goodsClient, arguments);
            BigDecimal discount = BigDecimal.ZERO;
            if (product.getOriginalPrice() != null && product.getPrice() != null
                    && product.getOriginalPrice().compareTo(product.getPrice()) > 0) {
                discount = product.getOriginalPrice().subtract(product.getPrice());
            }
            BigDecimal rate = BigDecimal.ONE;
            if (product.getOriginalPrice() != null && product.getPrice() != null
                    && product.getOriginalPrice().compareTo(BigDecimal.ZERO) > 0) {
                rate = product.getPrice().divide(product.getOriginalPrice(), 2, RoundingMode.HALF_UP);
            }
            return "商品：" + product.getName() + "，优惠金额：" + discount + "，折扣率：" + rate;
        }
    }
}
