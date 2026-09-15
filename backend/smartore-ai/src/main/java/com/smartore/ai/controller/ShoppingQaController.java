package com.smartore.ai.controller;

import com.smartore.common.result.Result;
import com.smartore.ai.entity.ShoppingQa;
import com.smartore.ai.entity.ShoppingQaRequest;
import com.smartore.ai.service.ShoppingQaService;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/shoppingQa")
public class ShoppingQaController {

    @Resource
    private ShoppingQaService shoppingQaService;

    @PostMapping("/ask")
    public Result ask(@RequestBody ShoppingQaRequest request) {
        ShoppingQa shoppingQa = shoppingQaService.ask(request);
        return Result.success(shoppingQa);
    }

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        shoppingQaService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        shoppingQaService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectPage")
    public Result selectPage(ShoppingQa shoppingQa,
                             @RequestParam(defaultValue = "1") Integer pageNum,
                             @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<ShoppingQa> pageInfo = shoppingQaService.selectPage(shoppingQa, pageNum, pageSize);
        return Result.success(pageInfo);
    }
}
