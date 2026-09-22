package com.smartore.goods.service;

import cn.hutool.core.util.StrUtil;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.api.StockOpRequest;
import com.smartore.goods.cache.GoodsCacheKeys;
import com.smartore.goods.cache.GoodsCacheNames;
import com.smartore.goods.entity.StockChangeRecord;
import com.smartore.goods.mapper.StockChangeRecordMapper;
import com.smartore.goods.mapper.StockMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 库存 Saga 步骤（仅交易域经 Feign 调用）。
 *
 * 幂等实现与钱包一致：先 INSERT IGNORE 变动流水（唯一键 biz_no+product_id+change_type），
 * 插入成功才动库存；整批在一次本地事务里，任一行库存不足则整批回滚（本服务内原子）。
 * 扣减用条件 UPDATE（available >= qty）防超卖，不再"先查后写"。
 */
@Service
public class StockSagaService {

    private static final Logger log = LoggerFactory.getLogger(StockSagaService.class);

    @Resource
    private StockMapper stockMapper;
    @Resource
    private StockChangeRecordMapper stockChangeRecordMapper;
    /**
     * 用 ObjectProvider 而非直接注入：缓存是可选的加速层（smartore.cache.enabled 默认 false），
     * 直接注入会把 CacheManager 变成启动硬依赖——关掉缓存服务就起不来，这不合理。
     */
    @Resource
    private ObjectProvider<CacheManager> cacheManagerProvider;

    @Transactional
    public void deductForOrder(StockOpRequest request) {
        validate(request);
        Set<Integer> touched = new LinkedHashSet<>();
        for (StockOpRequest.StockItem item : request.getItems()) {
            if (insertRecordIgnore(request.getOrderNo(), item.getProductId(),
                    StockChangeRecord.TYPE_DEDUCT, item.getQuantity())) {
                int rows = stockMapper.deductAvailable(item.getProductId(), item.getQuantity());
                if (rows == 0) {
                    throw new CustomException(ResultCodeEnum.STOCK_NOT_ENOUGH,
                            "商品库存不足（productId=" + item.getProductId() + "）");
                }
                stockMapper.syncProductDisplay(item.getProductId());
                touched.add(item.getProductId());
            }
        }
        evictAfterCommit(touched);
    }

    @Transactional
    public void restoreForOrder(StockOpRequest request) {
        validate(request);
        List<StockChangeRecord> deducted = stockChangeRecordMapper.selectByBizNoAndType(
                request.getOrderNo(), StockChangeRecord.TYPE_DEDUCT);
        if (deducted.isEmpty()) {
            // 该订单从未扣过库存（例如下单失败补偿），回补本身就是空操作
            return;
        }
        Set<Integer> touched = new LinkedHashSet<>();
        for (StockChangeRecord record : deducted) {
            if (insertRecordIgnore(request.getOrderNo(), record.getProductId(),
                    StockChangeRecord.TYPE_RESTORE, record.getQuantity())) {
                stockMapper.increaseAvailable(record.getProductId(), record.getQuantity());
                stockMapper.syncProductDisplay(record.getProductId());
                touched.add(record.getProductId());
            }
        }
        evictAfterCommit(touched);
    }

    /**
     * 精确失效被改动的商品单键（id=N），而不是清空整个商品缓存区。
     *
     * 两点考量：
     * 1. 只失效单键 → 下单前按 id 读的库存预检始终是新鲜的，不会因为缓存而放行一个早已无货的订单；
     *    列表页（键为筛选条件组合）则保留 TTL 内的展示延迟，不因每一笔成交被清空。
     * 2. 提交后再失效 → 事务未提交时清缓存，并发读会把旧值重新灌回去，等于没清。
     */
    private void evictAfterCommit(Set<Integer> productIds) {
        if (productIds.isEmpty()) {
            return;
        }
        CacheManager cacheManager = cacheManagerProvider.getIfAvailable();
        if (cacheManager == null) {
            // 未启用缓存：没有需要失效的条目
            return;
        }
        Runnable evict = () -> {
            Cache cache = cacheManager.getCache(GoodsCacheNames.PRODUCT);
            if (cache == null) {
                return;
            }
            for (Integer productId : productIds) {
                cache.evict(GoodsCacheKeys.byId(productId));
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        evict.run();
                    } catch (RuntimeException e) {
                        // 缓存失效失败只影响展示新鲜度（TTL 会兜底），不能反过来影响已提交的库存变更
                        log.warn("库存变动后失效商品缓存失败，等待 TTL 到期：{}", e.getMessage());
                    }
                }
            });
        } else {
            evict.run();
        }
    }

    private boolean insertRecordIgnore(String orderNo, Integer productId, String type, Integer quantity) {
        StockChangeRecord record = new StockChangeRecord();
        record.setBizNo(orderNo);
        record.setProductId(productId);
        record.setChangeType(type);
        record.setQuantity(quantity);
        if (stockChangeRecordMapper.insertIgnore(record) > 0) {
            return true;
        }
        // 0 行后回查甄别：回查到行=幂等重放（该步早已生效，返回 false 跳过库存变动）；
        // 查不到=写入因非唯一键原因被 INSERT IGNORE 吞掉，抛出让整批回滚，杜绝"假成功"
        boolean replayed = stockChangeRecordMapper.selectByBizNoAndType(orderNo, type).stream()
                .anyMatch(r -> productId.equals(r.getProductId()));
        if (replayed) {
            return false;
        }
        throw new CustomException(ResultCodeEnum.SYSTEM_ERROR, "库存流水写入失败（非唯一键冲突，整批回滚）");
    }

    private void validate(StockOpRequest request) {
        if (request == null || StrUtil.isBlank(request.getOrderNo())
                || request.getItems() == null || request.getItems().isEmpty()) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        for (StockOpRequest.StockItem item : request.getItems()) {
            if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new CustomException(ResultCodeEnum.PARAM_ERROR);
            }
        }
    }
}
