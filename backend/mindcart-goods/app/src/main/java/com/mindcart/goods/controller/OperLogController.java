package com.mindcart.goods.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mindcart.common.audit.OperLogEntry;
import com.mindcart.common.result.Result;
import com.mindcart.goods.mapper.OperLogMapper;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品域的操作审计查询（网关 admin-rules 限 ADMIN）。
 *
 * 只读，刻意不加 @OperationLog —— 查审计本身没有留痕价值，只会把表撑大。
 *
 * 注意审计表是分库的（每个服务在自己的库里留痕，不做跨库写同一张表）：
 * 想看 AI 侧配置改动要去 /aiOperLog，想查交易域要问交易服务。这是 database-per-service
 * 的固有代价，换来的是各服务不依赖一个"审计中心库"才能工作。
 */
@RestController
@RequestMapping("/operLog")
public class OperLogController {

    @Resource
    private OperLogMapper operLogMapper;

    @GetMapping("/selectPage")
    public Result<PageInfo<OperLogEntry>> selectPage(OperLogEntry condition,
                                                     @RequestParam(defaultValue = "1")
                                                     @Min(value = 1, message = "页码最小为1") Integer pageNum,
                                                     @RequestParam(defaultValue = "20")
                                                     @Min(value = 1, message = "每页条数最小为1")
                                                     @Max(value = 200, message = "每页条数最大为200") Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<OperLogEntry> list = operLogMapper.selectAll(condition);
        return Result.success(PageInfo.of(list));
    }

    /** 近 N 分钟的留痕条数：外部可据此接一个"管理端是否被异常高频操作"的探针 */
    @GetMapping("/recentCount")
    public Result<Integer> recentCount(@RequestParam(defaultValue = "60")
                                       @Min(value = 1, message = "分钟数最小为1")
                                       @Max(value = 10080, message = "分钟数最大为10080（7天）") Integer minutes) {
        return Result.success(operLogMapper.countRecent(minutes));
    }
}
