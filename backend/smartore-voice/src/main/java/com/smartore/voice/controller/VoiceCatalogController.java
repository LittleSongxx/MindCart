package com.smartore.voice.controller;

import com.smartore.common.result.Result;
import com.smartore.voice.integration.CatalogSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 目录同步管理端点。网关侧 /voice/** 整体要求登录，/voice/admin/** 叠加
 * voice-shopping.debug.enabled 闸门（生产默认关）。
 */
@RestController
@RequestMapping("/voice/admin")
@RequiredArgsConstructor
public class VoiceCatalogController {

    private final CatalogSyncService catalogSync;
    private final JdbcTemplate jdbc;

    /** 全量重建：从 goods 拉在售目录并刷新向量（embedding 只重算文本变化的商品） */
    @PostMapping("/reindex")
    public Result<CatalogSyncService.SyncReport> reindex() {
        return Result.success(catalogSync.syncAll());
    }

    @GetMapping("/catalog-stats")
    public Result<Map<String, Object>> stats() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM voice_product", Integer.class);
        Integer onSale = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_product WHERE status='ON_SALE'", Integer.class);
        Integer vectorized = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_product WHERE embedding IS NOT NULL", Integer.class);
        return Result.success(Map.of(
                "total", total == null ? 0 : total,
                "onSale", onSale == null ? 0 : onSale,
                "vectorized", vectorized == null ? 0 : vectorized));
    }
}
