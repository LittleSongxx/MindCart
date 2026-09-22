package com.mindcart.trade.controller;

import com.mindcart.common.result.Result;
import com.mindcart.trade.dto.ShoppingCartAddRequest;
import com.mindcart.trade.dto.ShoppingCartUpdateRequest;
import com.mindcart.trade.entity.ShoppingCart;
import com.mindcart.trade.service.ShoppingCartService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/shoppingCart")
public class ShoppingCartController {

    @Resource
    private ShoppingCartService shoppingCartService;

    @PostMapping("/add")
    public Result<Void> add(@Valid @RequestBody ShoppingCartAddRequest request) {
        shoppingCartService.add(request.toEntity());
        return Result.success();
    }

    @PutMapping("/update")
    public Result<Void> update(@Valid @RequestBody ShoppingCartUpdateRequest request) {
        shoppingCartService.updateById(request.toEntity());
        return Result.success();
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        shoppingCartService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result<Void> deleteBatch(@RequestBody List<Integer> ids) {
        shoppingCartService.deleteBatch(ids);
        return Result.success();
    }

    /** 我的购物车（userId 一律取当前登录用户） */
    @GetMapping("/selectAll")
    public Result<List<ShoppingCart>> selectAll(ShoppingCart condition) {
        return Result.success(shoppingCartService.selectMine());
    }
}
