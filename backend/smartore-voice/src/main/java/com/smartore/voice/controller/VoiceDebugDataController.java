package com.smartore.voice.controller;

import com.smartore.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评测用只读数据端点（debug 闸门 + 网关 ADMIN 规则双重保护）。
 */
@RestController
@RequestMapping("/voice/debug")
@RequiredArgsConstructor
public class VoiceDebugDataController {

    private final JdbcTemplate jdbc;

    /** 当前同步目录的 id→价格表（评测话术忠实度断言的实时基准，替代原项目的硬编码种子价目表） */
    @GetMapping("/catalog-prices")
    public Result<Map<Long, Double>> catalogPrices() {
        return Result.success(jdbc.query(
                "SELECT id, price FROM voice_product",
                (rs, i) -> Map.entry(rs.getLong("id"), rs.getDouble("price")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
}
