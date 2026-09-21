package com.smartore.goods.api;

import com.smartore.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品域对外契约。/internal/** 仅限集群内 Feign 调用，网关不路由。
 */
@FeignClient(name = "smartore-goods", contextId = "goodsClient")
public interface GoodsFeignClient {

    @GetMapping("/internal/product/{id}")
    Result<ProductVO> getProduct(@PathVariable("id") Integer id);

    /** 按关键词 + 价格上限检索在售候选（导购 Agent 工具用） */
    @GetMapping("/internal/product/search")
    Result<List<ProductVO>> searchOnSale(@RequestParam(value = "keyword", required = false) String keyword,
                                         @RequestParam(value = "maxPrice", required = false) java.math.BigDecimal maxPrice,
                                         @RequestParam(value = "limit", defaultValue = "8") Integer limit);

    /** 批量取商品现价/库存（购物车下单前校验与重新定价用） */
    @PostMapping("/internal/product/batch")
    Result<List<ProductVO>> getProducts(@RequestBody List<Integer> ids);

    /** 订单扣库存（幂等，库存不足抛 STOCK_NOT_ENOUGH，本服务内整批原子） */
    @PostMapping("/internal/stock/deduct")
    Result<Boolean> deductForOrder(@RequestBody StockOpRequest request);

    /** 订单取消回补库存（幂等） */
    @PostMapping("/internal/stock/restore")
    Result<Boolean> restoreForOrder(@RequestBody StockOpRequest request);

    /** 按ID/编号/名称解析商品（AI 工具与问答） */
    @GetMapping("/internal/product/resolve")
    Result<ProductVO> resolveProduct(@RequestParam(value = "id", required = false) Integer id,
                                     @RequestParam(value = "productNo", required = false) String productNo,
                                     @RequestParam(value = "name", required = false) String name);

    /** 同类/同品牌相似商品 */
    @GetMapping("/internal/product/similar")
    Result<List<ProductVO>> similarProducts(@RequestParam("categoryId") Integer categoryId,
                                            @RequestParam("brandId") Integer brandId,
                                            @RequestParam(value = "excludeId", required = false) Integer excludeId,
                                            @RequestParam(value = "limit", defaultValue = "5") Integer limit);

    /** 启用中的售后规则 */
    @GetMapping("/internal/afterSale/enabled")
    Result<List<AfterSaleRuleVO>> enabledAfterSaleRules();

    /** 商品详情+规格参数聚合（知识库一键导入源） */
    @GetMapping("/internal/product/knowledge-source/{productId}")
    Result<KnowledgeSourceVO> knowledgeSource(@PathVariable("productId") Integer productId);

    /** 批量查已评价的订单行ID */
    @GetMapping("/internal/review/reviewed-item-ids")
    Result<List<Integer>> reviewedItemIds(@RequestParam("ids") String ids);

    /** 商品已审核通过的评价 */
    @GetMapping("/internal/review/by-product/{productId}")
    Result<List<ProductReviewVO>> approvedReviews(@PathVariable("productId") Integer productId);

    /**
     * 分页列出在售商品（含类目/品牌名称），供 smartore-voice 全量同步目录+向量。
     * 只读；消费方翻页直到返回空页为止。
     */
    @GetMapping("/internal/product/listOnSale")
    Result<List<ProductSyncVO>> listOnSale(@RequestParam(value = "page", defaultValue = "1") Integer page,
                                           @RequestParam(value = "size", defaultValue = "100") Integer size);
}
