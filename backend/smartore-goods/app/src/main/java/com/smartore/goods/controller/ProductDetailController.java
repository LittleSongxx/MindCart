package com.smartore.goods.controller;

import com.smartore.common.result.Result;
import com.smartore.goods.entity.ProductDetail;
import com.smartore.goods.service.ProductDetailService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/productDetail")
public class ProductDetailController {

    @Resource
    private ProductDetailService productDetailService;

    @PostMapping("/save")
    public Result save(@RequestBody ProductDetail productDetail) {
        productDetailService.save(productDetail);
        return Result.success();
    }

    @GetMapping("/selectByProductId/{productId}")
    public Result selectByProductId(@PathVariable Integer productId) {
        ProductDetail productDetail = productDetailService.selectByProductId(productId);
        return Result.success(productDetail);
    }

}
