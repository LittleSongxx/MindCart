package com.smartore.ai.controller;

import com.smartore.ai.dto.AiModelConfigSaveRequest;
import com.smartore.ai.entity.AiModelConfig;
import com.smartore.ai.service.AiModelConfigService;
import com.smartore.common.audit.OperationLog;
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
@RequestMapping("/aiModelConfig")
public class AiModelConfigController {

    @Resource
    private AiModelConfigService aiModelConfigService;

    @OperationLog(module = "模型配置", action = "新增模型配置", recordArgs = false)
    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody AiModelConfigSaveRequest request) {
        aiModelConfigService.add(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "模型配置", action = "修改模型配置", recordArgs = false)
    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody AiModelConfigSaveRequest request) {
        aiModelConfigService.updateById(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "模型配置", action = "删除模型配置")
    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        aiModelConfigService.deleteById(id);
        return Result.success();
    }

    @OperationLog(module = "模型配置", action = "批量删除模型配置")
    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        aiModelConfigService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectPage")
    public Result selectPage(AiModelConfig aiModelConfig,
                            @RequestParam(defaultValue = "1")
                            @Min(value = 1, message = "页码最小为1") Integer pageNum,
                            @RequestParam(defaultValue = "10")
                            @Min(value = 1, message = "每页条数最小为1")
                            @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageInfo<AiModelConfig> pageInfo = aiModelConfigService.selectPage(aiModelConfig, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
