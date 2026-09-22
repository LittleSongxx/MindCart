package com.mindcart.goods.controller;

import com.mindcart.common.result.Result;
import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import com.mindcart.goods.dto.ProductParamSaveRequest;
import com.mindcart.goods.entity.ProductParam;
import com.mindcart.goods.service.ProductParamService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/productParam")
public class ProductParamController {

    @Resource
    private ProductParamService productParamService;

    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody ProductParamSaveRequest request) {
        productParamService.add(request.toEntity());
        return Result.success();
    }

    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody ProductParamSaveRequest request) {
        productParamService.updateById(request.toEntity());
        return Result.success();
    }

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        productParamService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        productParamService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectAll")
    public Result selectAll(ProductParam productParam) {
        List<ProductParam> list = productParamService.selectAll(productParam);
        return Result.success(list);
    }

}
