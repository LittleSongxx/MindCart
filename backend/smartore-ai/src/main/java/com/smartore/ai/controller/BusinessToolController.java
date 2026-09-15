package com.smartore.ai.controller;

import com.smartore.common.result.Result;
import com.smartore.ai.entity.BusinessToolRequest;
import com.smartore.ai.entity.BusinessToolResult;
import com.smartore.ai.service.BusinessToolService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/businessTool")
public class BusinessToolController {

    @Resource
    private BusinessToolService businessToolService;

    @PostMapping("/userProfile")
    public Result userProfile(@RequestBody BusinessToolRequest request) {
        BusinessToolResult result = businessToolService.queryUserProfile(request);
        return Result.success(result);
    }

    @PostMapping("/similarProducts")
    public Result similarProducts(@RequestBody BusinessToolRequest request) {
        BusinessToolResult result = businessToolService.querySimilarProducts(request);
        return Result.success(result);
    }

    @PostMapping("/orderStatus")
    public Result orderStatus(@RequestBody BusinessToolRequest request) {
        BusinessToolResult result = businessToolService.queryOrderStatus(request);
        return Result.success(result);
    }
}
