package com.smartore.trade.controller;

import com.github.pagehelper.PageInfo;
import com.smartore.common.result.Result;
import com.smartore.trade.entity.OrderCreateRequest;
import com.smartore.trade.entity.ShopOrder;
import com.smartore.trade.service.OrderSagaService;
import com.smartore.trade.service.ShopOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/shopOrder")
public class ShopOrderController {

    @Resource
    private ShopOrderService shopOrderService;
    @Resource
    private OrderSagaService orderSagaService;

    /** 下单（Saga 编排）：请求体需带 requestId 幂等键，前端每次提交生成、重试复用 */
    @PostMapping("/create")
    public Result<ShopOrder> create(@RequestBody OrderCreateRequest request) {
        return Result.success(orderSagaService.create(request));
    }

    @PutMapping("/ship/{id}")
    public Result<Void> ship(@PathVariable Integer id) {
        shopOrderService.ship(id);
        return Result.success();
    }

    @PutMapping("/finish/{id}")
    public Result<Void> finish(@PathVariable Integer id) {
        shopOrderService.finish(id);
        return Result.success();
    }

    @PutMapping("/cancel/{id}")
    public Result<Void> cancel(@PathVariable Integer id) {
        shopOrderService.cancel(id);
        return Result.success();
    }

    @GetMapping("/selectAll")
    public Result<List<ShopOrder>> selectAll(ShopOrder condition) {
        return Result.success(shopOrderService.selectAll(condition));
    }

    @GetMapping("/selectPage")
    public Result<PageInfo<ShopOrder>> selectPage(ShopOrder condition,
                                                  @RequestParam(defaultValue = "1") Integer pageNum,
                                                  @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(shopOrderService.selectPage(condition, pageNum, pageSize));
    }
}
