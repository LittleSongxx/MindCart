package com.smartore.ai.controller;

import com.smartore.ai.dto.ProductKnowledgeSaveRequest;
import com.smartore.ai.entity.ProductKnowledge;
import com.smartore.ai.service.ProductKnowledgeService;
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
@RequestMapping("/productKnowledge")
public class ProductKnowledgeController {

    @Resource
    private ProductKnowledgeService productKnowledgeService;

    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody ProductKnowledgeSaveRequest request) {
        productKnowledgeService.add(request.toEntity());
        return Result.success();
    }

    @PostMapping("/importFromProduct/{productId}")
    public Result importFromProduct(@PathVariable Integer productId) {
        int count = productKnowledgeService.importFromProduct(productId);
        return Result.success(count);
    }

    @PostMapping("/importAll")
    public Result importAll() {
        int count = productKnowledgeService.importAll();
        return Result.success(count);
    }

    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody ProductKnowledgeSaveRequest request) {
        productKnowledgeService.updateById(request.toEntity());
        return Result.success();
    }

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        productKnowledgeService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        productKnowledgeService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectAll")
    public Result selectAll(ProductKnowledge productKnowledge) {
        List<ProductKnowledge> list = productKnowledgeService.selectAll(productKnowledge);
        return Result.success(list);
    }

    @GetMapping("/selectPage")
    public Result selectPage(ProductKnowledge productKnowledge,
                             @RequestParam(defaultValue = "1")
                             @Min(value = 1, message = "页码最小为1") Integer pageNum,
                             @RequestParam(defaultValue = "10")
                             @Min(value = 1, message = "每页条数最小为1")
                             @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageInfo<ProductKnowledge> pageInfo = productKnowledgeService.selectPage(productKnowledge, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
