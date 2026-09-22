package com.smartore.goods.controller;

import com.smartore.common.audit.OperationLog;
import com.smartore.common.result.Result;
import com.smartore.common.validation.Create;
import com.smartore.common.validation.Update;
import com.smartore.goods.dto.ProductBrandSaveRequest;
import com.smartore.goods.entity.ProductBrand;
import com.smartore.goods.service.ProductBrandService;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/productBrand")
public class ProductBrandController {

    @Resource
    private ProductBrandService productBrandService;

    @OperationLog(module = "品牌管理", action = "新增品牌")
    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody ProductBrandSaveRequest request) {
        productBrandService.add(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "品牌管理", action = "修改品牌")
    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody ProductBrandSaveRequest request) {
        productBrandService.updateById(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "品牌管理", action = "删除品牌")
    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        productBrandService.deleteById(id);
        return Result.success();
    }

    @OperationLog(module = "品牌管理", action = "批量删除品牌")
    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        productBrandService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectAll")
    public Result selectAll(ProductBrand productBrand) {
        List<ProductBrand> list = productBrandService.selectAll(productBrand);
        return Result.success(list);
    }

    @GetMapping("/selectPage")
    public Result selectPage(ProductBrand productBrand,
                             @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为1") Integer pageNum,
                             @RequestParam(defaultValue = "10")
                             @Min(value = 1, message = "每页条数最小为1")
                             @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageInfo<ProductBrand> pageInfo = productBrandService.selectPage(productBrand, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
