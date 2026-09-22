package com.mindcart.goods.controller;

import com.mindcart.common.audit.OperationLog;
import com.mindcart.common.result.Result;
import com.mindcart.goods.dto.ProductReviewAuditRequest;
import com.mindcart.goods.dto.ProductReviewCreateRequest;
import com.mindcart.goods.entity.ProductReview;
import com.mindcart.goods.service.ProductReviewService;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/productReview")
public class ProductReviewController {

    @Resource
    private ProductReviewService productReviewService;

    @PostMapping("/add")
    public Result add(@Valid @RequestBody ProductReviewCreateRequest request) {
        productReviewService.add(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "评价管理", action = "审核评价")
    @PutMapping("/audit")
    public Result audit(@Valid @RequestBody ProductReviewAuditRequest request) {
        productReviewService.audit(request.toEntity());
        return Result.success();
    }

    @GetMapping("/selectAll")
    public Result selectAll(ProductReview productReview) {
        List<ProductReview> list = productReviewService.selectAll(productReview);
        return Result.success(list);
    }

    @GetMapping("/selectPage")
    public Result selectPage(ProductReview productReview,
                             @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为1") Integer pageNum,
                             @RequestParam(defaultValue = "10")
                             @Min(value = 1, message = "每页条数最小为1")
                             @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageInfo<ProductReview> pageInfo = productReviewService.selectPage(productReview, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
