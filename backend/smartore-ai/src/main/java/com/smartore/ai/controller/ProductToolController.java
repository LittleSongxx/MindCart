package com.smartore.ai.controller;

import com.smartore.common.result.Result;
import com.smartore.ai.entity.ProductToolRequest;
import com.smartore.ai.entity.ProductToolResult;
import com.smartore.ai.service.ProductToolService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/productTool")
public class ProductToolController {

    @Resource
    private ProductToolService productToolService;

    @PostMapping("/price")
    public Result price(@RequestBody ProductToolRequest request) {
        ProductToolResult result = productToolService.queryProductPrice(request);
        return Result.success(result);
    }

    @PostMapping("/stock")
    public Result stock(@RequestBody ProductToolRequest request) {
        ProductToolResult result = productToolService.queryProductStock(request);
        return Result.success(result);
    }

    @PostMapping("/promotion")
    public Result promotion(@RequestBody ProductToolRequest request) {
        ProductToolResult result = productToolService.queryProductPromotion(request);
        return Result.success(result);
    }
}
