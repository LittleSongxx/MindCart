package com.smartore.goods.controller;

import com.smartore.common.result.Result;
import com.smartore.goods.dto.ProductFavoriteAddRequest;
import com.smartore.goods.entity.ProductFavorite;
import com.smartore.goods.service.ProductFavoriteService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/productFavorite")
public class ProductFavoriteController {

    @Resource
    private ProductFavoriteService productFavoriteService;

    @PostMapping("/add")
    public Result add(@Valid @RequestBody ProductFavoriteAddRequest request) {
        productFavoriteService.add(request.toEntity());
        return Result.success();
    }

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        productFavoriteService.deleteById(id);
        return Result.success();
    }

    @GetMapping("/selectAll")
    public Result selectAll(ProductFavorite productFavorite) {
        List<ProductFavorite> list = productFavoriteService.selectAll(productFavorite);
        return Result.success(list);
    }
}
