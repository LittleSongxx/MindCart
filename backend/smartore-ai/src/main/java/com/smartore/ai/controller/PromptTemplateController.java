package com.smartore.ai.controller;

import com.smartore.ai.dto.PromptTemplateSaveRequest;
import com.smartore.ai.entity.PromptTemplate;
import com.smartore.ai.service.PromptTemplateService;
import com.smartore.common.result.Result;
import com.smartore.common.validation.Create;
import com.smartore.common.validation.Update;
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

    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody PromptTemplateSaveRequest request) {
        promptTemplateService.add(request.toEntity());
        return Result.success();
    }

    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody PromptTemplateSaveRequest request) {
        promptTemplateService.updateById(request.toEntity());
        return Result.success();
    }

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        promptTemplateService.deleteById(id);
        return Result.success();
    }

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
