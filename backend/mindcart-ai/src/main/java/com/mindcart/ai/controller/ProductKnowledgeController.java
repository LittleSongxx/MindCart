package com.mindcart.ai.controller;

import com.mindcart.ai.dto.ProductKnowledgeSaveRequest;
import com.mindcart.ai.entity.ProductKnowledge;
import com.mindcart.ai.service.ProductKnowledgeService;
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
@RequestMapping("/productKnowledge")
public class ProductKnowledgeController {

    @Resource
    private ProductKnowledgeService productKnowledgeService;

    @OperationLog(module = "知识库", action = "新增知识条目")
    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody ProductKnowledgeSaveRequest request) {
        productKnowledgeService.add(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "知识库", action = "从商品导入知识")
    @PostMapping("/importFromProduct/{productId}")
    public Result importFromProduct(@PathVariable Integer productId) {
        int count = productKnowledgeService.importFromProduct(productId);
        return Result.success(count);
    }

    @OperationLog(module = "知识库", action = "一键导入全部商品知识")
    @PostMapping("/importAll")
    public Result importAll() {
        int count = productKnowledgeService.importAll();
        return Result.success(count);
    }

    @OperationLog(module = "知识库", action = "修改知识条目（影响问答依据）")
    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody ProductKnowledgeSaveRequest request) {
        productKnowledgeService.updateById(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "知识库", action = "删除知识条目")
    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        productKnowledgeService.deleteById(id);
        return Result.success();
    }

    @OperationLog(module = "知识库", action = "批量删除知识条目")
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
