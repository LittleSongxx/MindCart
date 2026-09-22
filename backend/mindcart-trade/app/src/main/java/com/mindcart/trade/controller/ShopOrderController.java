package com.mindcart.trade.controller;

import com.github.pagehelper.PageInfo;
import com.mindcart.common.result.Result;
import com.mindcart.trade.entity.OrderCreateRequest;
import com.mindcart.trade.entity.ShopOrder;
import com.mindcart.trade.service.OrderSagaService;
import com.mindcart.trade.service.ShopOrderService;
import com.mindcart.trade.service.TradeEventPublisher;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/shopOrder")
public class ShopOrderController {

    @Resource
    private ShopOrderService shopOrderService;
    @Resource
    private OrderSagaService orderSagaService;
    @Resource
    private TradeEventPublisher tradeEventPublisher;

    /**
     * 下单（Saga 编排）：请求体需带 requestId 幂等键，前端每次提交生成、重试复用。
     * 校验放在进入 Saga 之前——Saga 内部有"保持 PAYING 交给恢复任务"的模糊失败路径，
     * 明显的参数错误不该走到那里去占用恢复任务的判定窗口。
     */
    @PostMapping("/create")
    public Result<ShopOrder> create(@Valid @RequestBody OrderCreateRequest request) {
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
                                                  @RequestParam(defaultValue = "1")
                                                  @Min(value = 1, message = "页码最小为1") Integer pageNum,
                                                  @RequestParam(defaultValue = "10")
                                                  @Min(value = 1, message = "每页条数最小为1")
                                                  @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        return Result.success(shopOrderService.selectPage(condition, pageNum, pageSize));
    }

    /** Outbox 账本水位（ADMIN）：PENDING/PUBLISHED/FAILED 各多少条，用于确认 Rabbit 故障影响面 */
    @GetMapping("/outboxStats")
    public Result<java.util.Map<String, Integer>> outboxStats() {
        return Result.success(tradeEventPublisher.stats());
    }

    /**
     * 复位重投耗尽的 Outbox 事件（ADMIN）。
     * 替代过去"只能手写 UPDATE 语句"的处置方式：Rabbit 故障恢复后调一次即可。
     */
    @PostMapping("/replayOutbox")
    public Result<Integer> replayOutbox() {
        return Result.success(tradeEventPublisher.replayExhausted());
    }
}
