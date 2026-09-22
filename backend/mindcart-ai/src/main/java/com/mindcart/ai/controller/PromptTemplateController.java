package com.mindcart.ai.controller;

import com.mindcart.ai.dto.PromptTemplateSaveRequest;
import com.mindcart.ai.entity.PromptTemplate;
import com.mindcart.ai.service.PromptTemplateService;
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
@RequestMapping("/promptTemplate")
public class PromptTemplateController {

    @Resource
    private PromptTemplateService promptTemplateService;

    @OperationLog(module = "提示词", action = "新增提示词模板")
    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody PromptTemplateSaveRequest request) {
        promptTemplateService.add(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "提示词", action = "修改提示词模板（影响全站回答）")
    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody PromptTemplateSaveRequest request) {
        promptTemplateService.updateById(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "提示词", action = "删除提示词模板")
    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        promptTemplateService.deleteById(id);
        return Result.success();
    }

    @OperationLog(module = "提示词", action = "批量删除提示词模板")
    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        promptTemplateService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectPage")
    public Result selectPage(PromptTemplate promptTemplate,
                            @RequestParam(defaultValue = "1")
                            @Min(value = 1, message = "页码最小为1") Integer pageNum,
                            @RequestParam(defaultValue = "10")
                            @Min(value = 1, message = "每页条数最小为1")
                            @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageInfo<PromptTemplate> pageInfo = promptTemplateService.selectPage(promptTemplate, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
