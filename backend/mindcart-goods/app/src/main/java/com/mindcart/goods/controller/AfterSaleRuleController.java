package com.mindcart.goods.controller;

import com.mindcart.common.audit.OperationLog;
import com.mindcart.common.result.Result;
import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import com.mindcart.goods.dto.AfterSaleRuleSaveRequest;
import com.mindcart.goods.entity.AfterSaleRule;
import com.mindcart.goods.service.AfterSaleRuleService;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/afterSaleRule")
public class AfterSaleRuleController {

    @Resource
    private AfterSaleRuleService afterSaleRuleService;

    @OperationLog(module = "售后规则", action = "新增售后规则")
    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody AfterSaleRuleSaveRequest request) {
        afterSaleRuleService.add(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "售后规则", action = "修改售后规则")
    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody AfterSaleRuleSaveRequest request) {
        afterSaleRuleService.updateById(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "售后规则", action = "删除售后规则")
    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        afterSaleRuleService.deleteById(id);
        return Result.success();
    }

    @OperationLog(module = "售后规则", action = "批量删除售后规则")
    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        afterSaleRuleService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectPage")
    public Result selectPage(AfterSaleRule afterSaleRule,
                             @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为1") Integer pageNum,
                             @RequestParam(defaultValue = "10")
                             @Min(value = 1, message = "每页条数最小为1")
                             @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageInfo<AfterSaleRule> pageInfo = afterSaleRuleService.selectPage(afterSaleRule, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
