package com.smartore.voice.integration;

import com.smartore.common.result.Result;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import com.smartore.voice.dto.RecommendedItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 推荐出口前的实时复核：voice_product 是同步副本，价格/库存/在售状态
 * 以 goods 服务实时数据为准（对齐底座的"AI 不持有交易事实"原则）。
 * <p>
 * 剔除下架/无货商品（防幻觉口播不存在的价格）；价格以实时值回填。
 * goods 不可用时 fail-open 降级为同步快照（30 分钟窗口内漂移有限，
 * 下单前 trade 侧还会按购物车实时重定价兜底）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductLiveVerifier {

    private final GoodsFeignClient goodsClient;

    public List<RecommendedItem> verify(List<RecommendedItem> items) {
        if (items == null || items.isEmpty()) return items;
        try {
            List<Integer> ids = items.stream().map(it -> it.productId().intValue()).toList();
            Result<List<ProductVO>> r = goodsClient.getProducts(ids);
            if (r == null || !"200".equals(r.getCode()) || r.getData() == null) {
                log.warn("[Verify] goods 复核返回异常 code={}，降级为快照数据", r == null ? "null" : r.getCode());
                return items;
            }
            Map<Long, ProductVO> live = r.getData().stream()
                    .collect(Collectors.toMap(vo -> vo.getId().longValue(), Function.identity()));
            List<RecommendedItem> out = new ArrayList<>();
            for (RecommendedItem it : items) {
                ProductVO vo = live.get(it.productId());
                if (vo == null || !"ON_SALE".equals(vo.getStatus())
                        || vo.getStockQuantity() == null || vo.getStockQuantity() <= 0) {
                    log.info("[Verify] 剔除下架/无货商品 id={} name={}", it.productId(), it.name());
                    continue;
                }
                BigDecimal livePrice = vo.getPrice() == null ? it.price() : vo.getPrice();
                out.add(new RecommendedItem(it.productId(), it.name(), livePrice,
                        it.reason(), it.matchScore(), it.attributes()));
            }
            return out;
        } catch (Exception e) {
            log.warn("[Verify] goods 复核不可用，降级为快照数据: {}", e.getMessage());
            return items;
        }
    }
}
