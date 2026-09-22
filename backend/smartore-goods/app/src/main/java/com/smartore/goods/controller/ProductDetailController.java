package com.smartore.goods.controller;

import com.smartore.common.result.Result;
import com.smartore.goods.dto.ProductDetailSaveRequest;
import com.smartore.goods.entity.ProductDetail;
import com.smartore.goods.service.ProductDetailService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/productDetail")
public class ProductDetailController {

    @Resource
    private ProductDetailService productDetailService;

    /** 单入口（服务层按 productId 决定插入还是更新），无新增/更新分组之分 */
    @PostMapping("/save")
    public Result save(@Valid @RequestBody ProductDetailSaveRequest request) {
        productDetailService.save(request.toEntity());
        return Result.success();
    }

    @GetMapping("/selectByProductId/{productId}")
    public Result selectByProductId(@PathVariable Integer productId) {
        ProductDetail productDetail = productDetailService.selectByProductId(productId);
        return Result.success(productDetail);
    }

}
