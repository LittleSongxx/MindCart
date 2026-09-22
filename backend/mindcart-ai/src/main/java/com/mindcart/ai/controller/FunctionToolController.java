package com.mindcart.ai.controller;

import com.mindcart.ai.dto.FunctionToolSaveRequest;
import com.mindcart.ai.entity.FunctionTool;
import com.mindcart.ai.service.FunctionToolService;
import com.mindcart.common.audit.OperationLog;
import com.mindcart.common.result.Result;
import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/functionTool")
public class FunctionToolController {

    @Resource
    private FunctionToolService functionToolService;

    @OperationLog(module = "工具配置", action = "新增函数工具")
    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody FunctionToolSaveRequest request) {
        functionToolService.add(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "工具配置", action = "修改函数工具")
    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody FunctionToolSaveRequest request) {
        functionToolService.updateById(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "工具配置", action = "删除函数工具")
    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        functionToolService.deleteById(id);
        return Result.success();
    }

    @OperationLog(module = "工具配置", action = "批量删除函数工具")
    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        functionToolService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectPage")
    public Result selectPage(FunctionTool functionTool,
                            @RequestParam(defaultValue = "1")
                            @Min(value = 1, message = "页码最小为1") Integer pageNum,
                            @RequestParam(defaultValue = "10")
                            @Min(value = 1, message = "每页条数最小为1")
                            @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageInfo<FunctionTool> pageInfo = functionToolService.selectPage(functionTool, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
