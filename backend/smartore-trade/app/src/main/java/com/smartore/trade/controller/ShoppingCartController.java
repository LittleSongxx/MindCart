package com.smartore.trade.controller;

import com.smartore.common.result.Result;
import com.smartore.trade.entity.ShoppingCart;
import com.smartore.trade.service.ShoppingCartService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/shoppingCart")
public class ShoppingCartController {

    @Resource
    private ShoppingCartService shoppingCartService;

    @PostMapping("/add")
    public Result<Void> add(@RequestBody ShoppingCart shoppingCart) {
        shoppingCartService.add(shoppingCart);
        return Result.success();
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody ShoppingCart shoppingCart) {
        shoppingCartService.updateById(shoppingCart);
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
