package com.smartore.goods.internal;

import com.github.pagehelper.PageHelper;
import com.smartore.common.result.Result;
import com.smartore.goods.api.ProductVO;
import com.smartore.goods.api.StockOpRequest;
import com.smartore.goods.entity.Product;
import com.smartore.goods.mapper.ProductMapper;
import com.smartore.goods.service.StockSagaService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 集群内内部接口：商品快照、候选检索、批量现价、库存 Saga。网关不路由 /internal/**。
 */
@RestController
@RequestMapping("/internal")
public class InternalGoodsController {

    @Resource
    private ProductMapper productMapper;
    @Resource
    private StockSagaService stockSagaService;
    @Resource
    private com.smartore.goods.service.AfterSaleRuleService afterSaleRuleService;
    @Resource
    private com.smartore.goods.mapper.ProductReviewMapper productReviewMapper;
    @Resource
    private com.smartore.goods.mapper.ProductDetailMapper productDetailMapper;
    @Resource
    private com.smartore.goods.mapper.ProductParamMapper productParamMapper;

    private com.smartore.goods.api.ProductReviewVO toReviewVO(com.smartore.goods.entity.ProductReview r) {
        com.smartore.goods.api.ProductReviewVO vo = new com.smartore.goods.api.ProductReviewVO();
        vo.setId(r.getId());
        vo.setProductId(r.getProductId());
        vo.setUserId(r.getUserId());
        vo.setRating(r.getRating());
        vo.setContent(r.getContent());
        vo.setCreateTime(r.getCreateTime());
        return vo;
    }

    private com.smartore.goods.api.AfterSaleRuleVO toRuleVO(com.smartore.goods.entity.AfterSaleRule rule) {
        com.smartore.goods.api.AfterSaleRuleVO vo = new com.smartore.goods.api.AfterSaleRuleVO();
        vo.setId(rule.getId());
        vo.setRuleName(rule.getRuleName());
        vo.setRuleType(rule.getRuleType());
        vo.setCategoryId(rule.getCategoryId());
        vo.setApplyScene(rule.getApplyScene());
        vo.setConditionText(rule.getConditionText());
        vo.setProcessText(rule.getProcessText());
        vo.setTimeLimit(rule.getTimeLimit());
        vo.setContactChannel(rule.getContactChannel());
        return vo;
    }

    @GetMapping("/product/{id}")
    public Result<ProductVO> getProduct(@PathVariable Integer id) {
        Product condition = new Product();
        condition.setId(id);
        List<Product> list = productMapper.selectAll(condition);
        return Result.success(list.isEmpty() ? null : toVO(list.get(0)));
    }

    @GetMapping("/product/search")
    public Result<List<ProductVO>> searchOnSale(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) BigDecimal maxPrice,
                                                @RequestParam(defaultValue = "8") Integer limit) {
        Product condition = new Product();
        condition.setStatus("ON_SALE");
        if (keyword != null && !keyword.isBlank()) {
            condition.setName(keyword);
        }
        // 分页只取一页，用 PageHelper 控制返回量，避免全表载入
        PageHelper.startPage(1, Math.max(1, Math.min(limit == null ? 8 : limit, 50)));
        List<ProductVO> result = productMapper.selectAll(condition).stream()
                .filter(p -> maxPrice == null || p.getPrice() == null || p.getPrice().compareTo(maxPrice) <= 0)
                .map(this::toVO)
                .toList();
        return Result.success(result);
    }

    @PostMapping("/product/batch")
    public Result<List<ProductVO>> getProducts(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.success(List.of());
        }
        return Result.success(ids.stream()
                .map(this::findProduct)
                .filter(p -> p != null)
                .map(this::toVO)
                .toList());
    }

    /** 按ID/编号/名称解析单个商品（AI 工具与问答的商品定位） */
    @GetMapping("/product/resolve")
    public Result<ProductVO> resolveProduct(@RequestParam(required = false) Integer id,
                                            @RequestParam(required = false) String productNo,
                                            @RequestParam(required = false) String name) {
        Product product = null;
        if (id != null) {
            Product condition = new Product();
            condition.setId(id);
            List<Product> list = productMapper.selectAll(condition);
            product = list.isEmpty() ? null : list.get(0);
        } else if (productNo != null && !productNo.isBlank()) {
            product = productMapper.selectByProductNo(productNo);
        } else if (name != null && !name.isBlank()) {
            Product condition = new Product();
            condition.setName(name);
            List<Product> list = productMapper.selectAll(condition);
            product = list.isEmpty() ? null : list.get(0);
        }
        return Result.success(product == null ? null : toVO(product));
    }

    /** 同类/同品牌相似商品（用户画像工具） */
    @GetMapping("/product/similar")
    public Result<List<ProductVO>> similarProducts(@RequestParam Integer categoryId,
                                                   @RequestParam Integer brandId,
                                                   @RequestParam(required = false) Integer excludeId,
                                                   @RequestParam(defaultValue = "5") Integer limit) {
        List<ProductVO> result = new java.util.ArrayList<>();
        Product condition = new Product();
        condition.setCategoryId(categoryId);
        condition.setStatus("ON_SALE");
        for (Product p : productMapper.selectAll(condition)) {
            if (excludeId != null && excludeId.equals(p.getId())) {
                continue;
            }
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
            result.add(toVO(p));
        }
        if (result.size() < Math.max(1, limit)) {
            Product brandCondition = new Product();
            brandCondition.setBrandId(brandId);
            brandCondition.setStatus("ON_SALE");
            for (Product p : productMapper.selectAll(brandCondition)) {
                if (excludeId != null && excludeId.equals(p.getId())) {
                    continue;
                }
                if (result.size() >= Math.max(1, limit)) {
                    break;
                }
                boolean dup = result.stream().anyMatch(v -> v.getId().equals(p.getId()));
                if (!dup) {
                    result.add(toVO(p));
                }
            }
        }
        return Result.success(result);
    }

    /** 启用中的售后规则（AI 售后问答证据源） */
    @GetMapping("/afterSale/enabled")
    public Result<List<com.smartore.goods.api.AfterSaleRuleVO>> enabledAfterSaleRules() {
        com.smartore.goods.entity.AfterSaleRule condition = new com.smartore.goods.entity.AfterSaleRule();
        condition.setIsEnabled(1);
        return Result.success(afterSaleRuleService.selectAll(condition).stream().map(this::toRuleVO).toList());
    }

    /** 商品详情+规格参数聚合（AI 知识库一键导入源） */
    @GetMapping("/product/knowledge-source/{productId}")
    public Result<com.smartore.goods.api.KnowledgeSourceVO> knowledgeSource(@PathVariable Integer productId) {
        com.smartore.goods.api.KnowledgeSourceVO vo = new com.smartore.goods.api.KnowledgeSourceVO();
        Product product = findProduct(productId);
        if (product == null) {
            return Result.success(null);
        }
        vo.setProductId(product.getId());
        vo.setProductName(product.getName());
        com.smartore.goods.entity.ProductDetail detail = productDetailMapper.selectByProductId(productId);
        if (detail != null) {
            vo.setDetailContent(detail.getDetailContent());
            vo.setPackageInfo(detail.getPackageInfo());
            vo.setAfterSaleInfo(detail.getAfterSaleInfo());
        }
        com.smartore.goods.entity.ProductParam condition = new com.smartore.goods.entity.ProductParam();
        condition.setProductId(productId);
        vo.setParams(productParamMapper.selectAll(condition).stream().map(param -> {
            com.smartore.goods.api.KnowledgeSourceVO.ParamEntry entry = new com.smartore.goods.api.KnowledgeSourceVO.ParamEntry();
            entry.setParamGroup(param.getParamGroup());
            entry.setParamName(param.getParamName());
            entry.setParamValue(param.getParamValue());
            return entry;
        }).toList());
        return Result.success(vo);
    }

    /** 批量查已评价的订单行ID（trade 组装订单列表用，替代跨库 JOIN） */
    @GetMapping("/review/reviewed-item-ids")
    public Result<List<Integer>> reviewedItemIds(@RequestParam String ids) {
        List<Integer> orderItemIds = java.util.Arrays.stream(ids.split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).map(Integer::valueOf).toList();
        if (orderItemIds.isEmpty()) {
            return Result.success(List.of());
        }
        List<Integer> reviewed = new java.util.ArrayList<>();
        for (Integer orderItemId : orderItemIds) {
            if (productReviewMapper.selectByOrderItemId(orderItemId) != null) {
                reviewed.add(orderItemId);
            }
        }
        return Result.success(reviewed);
    }

    /** 商品已审核通过的评价（AI 评价分析证据源） */
    @GetMapping("/review/by-product/{productId}")
    public Result<List<com.smartore.goods.api.ProductReviewVO>> approvedReviews(@PathVariable Integer productId) {
        com.smartore.goods.entity.ProductReview condition = new com.smartore.goods.entity.ProductReview();
        condition.setProductId(productId);
        condition.setAuditStatus("APPROVED");
        return Result.success(productReviewMapper.selectAll(condition).stream().map(this::toReviewVO).toList());
    }

    @PostMapping("/stock/deduct")
    public Result<Boolean> deductForOrder(@RequestBody StockOpRequest request) {
        stockSagaService.deductForOrder(request);
        return Result.success(true);
    }

    @PostMapping("/stock/restore")
    public Result<Boolean> restoreForOrder(@RequestBody StockOpRequest request) {
        stockSagaService.restoreForOrder(request);
        return Result.success(true);
    }

    private Product findProduct(Integer id) {
        Product condition = new Product();
        condition.setId(id);
        List<Product> list = productMapper.selectAll(condition);
        return list.isEmpty() ? null : list.get(0);
    }

    private ProductVO toVO(Product product) {
        ProductVO vo = new ProductVO();
        vo.setId(product.getId());
        vo.setProductNo(product.getProductNo());
        vo.setName(product.getName());
        vo.setCoverImage(product.getCoverImage());
        vo.setPrice(product.getPrice());
        vo.setOriginalPrice(product.getOriginalPrice());
        vo.setTags(product.getTags());
        vo.setSellingPoint(product.getSellingPoint());
        vo.setStatus(product.getStatus());
        vo.setStockQuantity(product.getStockQuantity());
        vo.setCategoryId(product.getCategoryId());
        vo.setBrandId(product.getBrandId());
        vo.setIsRecommend(product.getIsRecommend());
        vo.setSort(product.getSort());
        return vo;
    }
}
