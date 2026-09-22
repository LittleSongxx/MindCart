package com.mindcart.ai.controller;

import com.mindcart.common.result.Result;
import com.mindcart.ai.entity.ProductToolRequest;
import com.mindcart.ai.entity.ProductToolResult;
import com.mindcart.ai.service.ProductToolService;
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
