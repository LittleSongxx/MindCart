package com.smartore.voice.integration;

import com.smartore.common.result.Result;
import com.smartore.trade.api.OrderFeignClient;
import com.smartore.trade.api.OrderBriefVO;
import com.smartore.voice.dto.UserProfileSnapshot;
import com.smartore.voice.entity.UserProfileDynamicEntity;
import com.smartore.voice.repository.UserProfileDynamicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 画像的订单侧补全：把用户在 Smartore 的真实历史订单回流到动态画像
 * （此前画像只从语音会话里学，文本会话与历史订单都看不到）。
 * <p>
 * 事实源是 trade 服务（OrderFeignClient.recent），这里只更新本地副本；
 * 失败静默降级——画像新鲜度不值得阻塞一次语音对话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileOrderSync {

    private final OrderFeignClient orderClient;
    private final UserProfileDynamicRepository dynRepo;

    @Async
    @CacheEvict(value = "userProfileSnapshot", key = "#userId")
    public void syncRecentOrders(Long userId) {
        try {
            Result<List<Integer>> r = orderClient.recentProductIds(userId.intValue(), 5);
            List<Integer> purchased = (r != null && "200".equals(r.getCode())) ? r.getData() : null;
            if (purchased == null || purchased.isEmpty()) return;

            UserProfileDynamicEntity e = dynRepo.findById(userId).orElseGet(() -> {
                UserProfileDynamicEntity n = new UserProfileDynamicEntity();
                n.setUserId(userId);
                return n;
            });
            // 与既有最近购买合并（去重保序：新订单在前，最多留 20 个）
            LinkedHashSet<Long> merged = new LinkedHashSet<>();
            purchased.forEach(id -> merged.add(id.longValue()));
            List<Long> existing = e.getRecentPurchased();
            if (existing != null) {
                for (Long id : existing) merged.add(id);
            }
            e.setRecentPurchased(merged.stream().limit(20).toList());
            dynRepo.save(e);
            log.info("[ProfileSync] 历史订单回流 userId={} 商品数={}", userId, purchased.size());
        } catch (Exception e) {
            log.warn("[ProfileSync] 订单回流失败（忽略）userId={}: {}", userId, e.getMessage());
        }
    }

    /** 供 debug/管理端查看合并后的画像 */
    public UserProfileSnapshot snapshot(Long userId) {
        return dynRepo.findById(userId)
                .map(d -> new UserProfileSnapshot(userId, null, null, null, null, null, null,
                        d.getCategoryAffinity(), d.getBrandAffinity(),
                        d.getRecentViewed(), d.getRecentPurchased(),
                        d.getPriceSensitivity(), d.getAvgOrderAmount()))
                .orElse(new UserProfileSnapshot(userId, null, null, null, null, null, null,
                        java.util.Map.of(), java.util.Map.of(), List.of(), List.of(), null, null));
    }
}
