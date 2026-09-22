package com.smartore.goods.controller;

import com.smartore.common.result.Result;
import com.smartore.common.validation.Create;
import com.smartore.common.validation.Update;
import com.smartore.goods.dto.ProductCategorySaveRequest;
import com.smartore.goods.entity.ProductCategory;
import com.smartore.goods.service.ProductCategoryService;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/productCategory")
public class ProductCategoryController {

    @Resource
    private ProductCategoryService productCategoryService;

    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody ProductCategorySaveRequest request) {
        productCategoryService.add(request.toEntity());
        return Result.success();
    }

    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody ProductCategorySaveRequest request) {
        productCategoryService.updateById(request.toEntity());
        return Result.success();
    }

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        productCategoryService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        productCategoryService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectAll")
    public Result selectAll(ProductCategory productCategory) {
        List<ProductCategory> list = productCategoryService.selectAll(productCategory);
        return Result.success(list);
    }

    @GetMapping("/selectPage")
    public Result selectPage(ProductCategory productCategory,
                             @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为1") Integer pageNum,
                             @RequestParam(defaultValue = "10")
                             @Min(value = 1, message = "每页条数最小为1")
                             @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageInfo<ProductCategory> pageInfo = productCategoryService.selectPage(productCategory, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
