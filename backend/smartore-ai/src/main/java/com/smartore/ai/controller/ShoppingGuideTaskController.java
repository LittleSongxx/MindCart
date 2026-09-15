package com.smartore.ai.controller;

import com.github.pagehelper.PageInfo;
import com.smartore.ai.entity.ShoppingGuideTask;
import com.smartore.ai.service.ShoppingGuideTaskService;
import com.smartore.common.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 导购任务接口。/execute/{id} 语义从"同步跑完"改为"入队异步执行"，
 * 前端提交后轮询任务状态与 AgentStep 轨迹。
 */
@RestController
@RequestMapping("/shoppingGuideTask")
public class ShoppingGuideTaskController {

    @Resource
    private ShoppingGuideTaskService shoppingGuideTaskService;

    @PostMapping("/add")
    public Result<Void> add(@RequestBody ShoppingGuideTask task) {
        shoppingGuideTaskService.add(task);
        return Result.success();
    }

    @PutMapping("/update")
    public Result<Void> update(@RequestBody ShoppingGuideTask task) {
        shoppingGuideTaskService.updateById(task);
        return Result.success();
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        shoppingGuideTaskService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result<Void> deleteBatch(@RequestBody List<Integer> ids) {
        shoppingGuideTaskService.deleteBatch(ids);
        return Result.success();
    }

    /** 入队异步执行（幂等：重复提交时消费者以任务状态为准） */
    @PostMapping("/execute/{id}")
    public Result<Void> execute(@PathVariable Integer id) {
        shoppingGuideTaskService.submit(id);
        return Result.success();
    }

    @GetMapping("/selectAll")
    public Result<List<ShoppingGuideTask>> selectAll(ShoppingGuideTask condition) {
        return Result.success(shoppingGuideTaskService.selectAll(condition));
    }

    @GetMapping("/selectPage")
    public Result<PageInfo<ShoppingGuideTask>> selectPage(ShoppingGuideTask condition,
                                                          @RequestParam(defaultValue = "1") Integer pageNum,
                                                          @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(shoppingGuideTaskService.selectPage(condition, pageNum, pageSize));
    }
}
