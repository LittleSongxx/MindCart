package com.mindcart.ai.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mindcart.ai.mapper.OperLogMapper;
import com.mindcart.common.audit.OperLogEntry;
import com.mindcart.common.result.Result;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 侧的操作审计查询（网关 admin-rules 限 ADMIN）。
 *
 * 路径用 /aiOperLog 而不是 /operLog：审计表分库，两个服务各有自己的留痕，
 * 网关上一个前缀只能路由到一个服务，因此按服务分开命名（不用跨库聚合这类额外机制）。
 */
@RestController
@RequestMapping("/aiOperLog")
public class AiOperLogController {

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

    @GetMapping("/recentCount")
    public Result<Integer> recentCount(@RequestParam(defaultValue = "60")
                                       @Min(value = 1, message = "分钟数最小为1")
                                       @Max(value = 10080, message = "分钟数最大为10080（7天）") Integer minutes) {
        return Result.success(operLogMapper.countRecent(minutes));
    }
}
