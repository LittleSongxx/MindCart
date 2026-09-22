package com.mindcart.ai.service;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.Result;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.ai.entity.ProductToolRequest;
import com.mindcart.ai.entity.ProductToolResult;
import com.mindcart.goods.api.GoodsFeignClient;
import com.mindcart.goods.api.ProductVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 商品工具（价格/库存/优惠）：数据从商品服务实时取，AI 侧只做视图组装。
 */
@Service
public class ProductToolService {

    @Resource
    private GoodsFeignClient goodsClient;

    public ProductToolResult queryProductPrice(ProductToolRequest request) {
        ProductVO product = resolveProduct(request);
        ProductToolResult result = base("PRODUCT_PRICE_QUERY", product, request);
        result.setPrice(product.getPrice());
        result.setOriginalPrice(product.getOriginalPrice());
        BigDecimal quantity = BigDecimal.valueOf(request.getQuantity() == null ? 1 : request.getQuantity());
        if (product.getPrice() != null) {
            result.setTotalAmount(product.getPrice().multiply(quantity));
        }
        result.setMessage("现价 " + product.getPrice() + "，原价 " + product.getOriginalPrice());
        return result;
    }

    public ProductToolResult queryProductStock(ProductToolRequest request) {
        ProductVO product = resolveProduct(request);
        ProductToolResult result = base("PRODUCT_STOCK_QUERY", product, request);
        int stock = product.getStockQuantity() == null ? 0 : product.getStockQuantity();
        int need = request.getQuantity() == null ? 1 : request.getQuantity();
        result.setStockQuantity(stock);
        result.setCanBuy(stock >= need ? 1 : 0);
        result.setMessage(stock >= need ? "库存充足，可购买" : "库存不足，当前库存 " + stock);
        return result;
    }

    public ProductToolResult queryProductPromotion(ProductToolRequest request) {
        ProductVO product = resolveProduct(request);
        ProductToolResult result = base("PRODUCT_PROMOTION_QUERY", product, request);
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal rate = BigDecimal.ONE;
        if (product.getOriginalPrice() != null && product.getPrice() != null
                && product.getOriginalPrice().compareTo(product.getPrice()) > 0) {
            discount = product.getOriginalPrice().subtract(product.getPrice());
            if (product.getOriginalPrice().compareTo(BigDecimal.ZERO) > 0) {
                rate = product.getPrice().divide(product.getOriginalPrice(), 2, RoundingMode.HALF_UP);
            }
        }
        result.setDiscountAmount(discount);
        result.setDiscountRate(rate);
        result.setMessage(discount.compareTo(BigDecimal.ZERO) > 0
                ? "优惠 " + discount + "（折扣率 " + rate + "）" : "当前无优惠活动");
        return result;
    }

    private ProductToolResult base(String toolCode, ProductVO product, ProductToolRequest request) {
        ProductToolResult result = new ProductToolResult();
        result.setToolCode(toolCode);
        result.setProductId(product.getId());
        result.setProductNo(product.getProductNo());
        result.setProductName(product.getName());
        result.setStatus(product.getStatus());
        result.setQuantity(request.getQuantity() == null ? 1 : request.getQuantity());
        return result;
    }

    private ProductVO resolveProduct(ProductToolRequest request) {
        if (ObjectUtil.isEmpty(request)) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        ProductVO product = unwrap(goodsClient.resolveProduct(
                request.getProductId(), request.getProductNo(), request.getProductName()));
        if (product == null) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "商品不存在");
        }
        return product;
    }

    /** 兼容旧字段：相似商品查询入口（BusinessToolService 使用） */
    public List<ProductVO> similarProducts(Integer categoryId, Integer brandId, Integer excludeId, int limit) {
        return unwrap(goodsClient.similarProducts(categoryId, brandId, excludeId, limit));
    }

    private <T> T unwrap(Result<T> result) {
        if (result == null || !ResultCodeEnum.SUCCESS.getCode().equals(result.getCode())) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR,
                    StrUtil.blankToDefault(result == null ? null : result.getMsg(), "商品服务不可用"));
        }
        return result.getData();
    }
}
