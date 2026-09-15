package com.smartore.ai.controller;

import com.smartore.common.result.Result;
import com.smartore.ai.entity.AgentStep;
import com.smartore.ai.service.AgentStepService;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/agentStep")
public class AgentStepController {

    @Resource
    private AgentStepService agentStepService;

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        agentStepService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        agentStepService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectPage")
    public Result selectPage(AgentStep agentStep,
                             @RequestParam(defaultValue = "1") Integer pageNum,
                             @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<AgentStep> pageInfo = agentStepService.selectPage(agentStep, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
