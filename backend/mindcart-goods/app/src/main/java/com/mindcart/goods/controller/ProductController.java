package com.mindcart.goods.controller;

import com.mindcart.common.audit.OperationLog;
import com.mindcart.common.result.Result;
import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import com.mindcart.goods.dto.ProductSaveRequest;
import com.mindcart.goods.entity.Product;
import com.mindcart.goods.service.ProductService;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/product")
public class ProductController {

    @Resource
    private ProductService productService;

    /** 新增：Create 分组（必填字段全查） */
    @OperationLog(module = "商品管理", action = "新增商品")
    @PostMapping("/add")
    public Result add(@Validated(Create.class) @RequestBody ProductSaveRequest request) {
        productService.add(request.toEntity());
        return Result.success();
    }

    /** 更新：Update 分组（只要求主键，其余字段缺省即不改；字段边界仍会校验） */
    @OperationLog(module = "商品管理", action = "修改商品（含改价/改库存/上下架）")
    @PutMapping("/update")
    public Result update(@Validated(Update.class) @RequestBody ProductSaveRequest request) {
        productService.updateById(request.toEntity());
        return Result.success();
    }

    @OperationLog(module = "商品管理", action = "删除商品")
    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        productService.deleteById(id);
        return Result.success();
    }

    @OperationLog(module = "商品管理", action = "批量删除商品")
    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        productService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectAll")
    public Result selectAll(Product product) {
        List<Product> list = productService.selectAll(product);
        return Result.success(list);
    }

    /** pageSize 上限 200：不加约束时 pageSize=1000000 会把全表拉进内存 */
    @GetMapping("/selectPage")
    public Result selectPage(Product product,
                             @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码最小为1") Integer pageNum,
                             @RequestParam(defaultValue = "10")
                             @Min(value = 1, message = "每页条数最小为1")
                             @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageInfo<Product> pageInfo = productService.selectPage(product, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
