package com.mindcart.voice.service;

import com.mindcart.voice.entity.ProductEntity;
import com.mindcart.voice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.ResultCodeEnum;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 语音下单协调（前端代执行模型，trade 服务零改动）。
 * <p>
 * 本服务不产生任何交易事实：语音确认后生成 order_action 动作帧，由前端
 * 以用户自己的登录态走 MindCart 现成链路（加购 → /shopOrder/create），
 * requestId 幂等键由此处生成并随动作帧透传，交易侧的 Saga/幂等/防超卖原样生效。
 * 结果经 WS 控制帧 order_result 回传，{@link #completeOnResult} 收口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceOrderService {

    private final ProductRepository productRepo;
    private final PendingOrderStore pendingStore;
    private final StringRedisTemplate redis;
    private final com.mindcart.goods.api.GoodsFeignClient goodsClient;
    private final com.mindcart.voice.repository.VoiceOrderEventRepository orderEventRepo;

    /** 已派发标记：挡重复确认（语音"确认确认"连说/前端重试），与 requestId 幂等键双保险 */
    private static final String DISPATCHED_KEY = "vs:order_dispatched:";
    /** 最近成交单：供"取消刚才的订单"这类指代解析 */
    private static final String LAST_ORDER_KEY = "vs:last_order:";

    public PendingOrderStore.PendingOrder preview(String sessionId, Long userId, Long productId, int qty) {
        ProductEntity p = productRepo.findById(productId)
                .orElseThrow(() -> new CustomException(ResultCodeEnum.PARAM_ERROR, "商品不存在或已下架"));
        // 起 pending 单前实时复核：下架/无货不起单，价格以 goods 实时为准（快照可能滞后）
        BigDecimal price = p.getPrice();
        try {
            var r = goodsClient.getProduct(productId.intValue());
            var vo = (r != null && "200".equals(r.getCode())) ? r.getData() : null;
            if (vo == null || !"ON_SALE".equals(vo.getStatus())
                    || vo.getStockQuantity() == null || vo.getStockQuantity() < qty) {
                throw new CustomException(ResultCodeEnum.STOCK_NOT_ENOUGH, "这款商品暂时缺货或已下架");
            }
            if (vo.getPrice() != null) price = vo.getPrice();
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[VoiceOrder] preview 实时复核不可用，降级为同步快照价: {}", e.getMessage());
        }
        BigDecimal total = price.multiply(BigDecimal.valueOf(qty));
        String requestId = "voice-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var po = new PendingOrderStore.PendingOrder(
                sessionId, userId, productId, p.getName(), qty, price, total, requestId);
        pendingStore.put(po);
        return po;
    }

    /**
     * 语音确认下单：生成给前端的下单动作帧。
     *
     * @return 动作帧；无 pending 或该单已派发（重复确认）时返回 null
     */
    public Map<String, Object> confirm(String sessionId) {
        PendingOrderStore.PendingOrder po = pendingStore.get(sessionId);
        if (po == null) return null;
        Boolean first = redis.opsForValue()
                .setIfAbsent(DISPATCHED_KEY + po.requestId(), "1", Duration.ofMinutes(15));
        if (!Boolean.TRUE.equals(first)) {
            log.info("[VoiceOrder] 重复确认被拦截 sessionId={} requestId={}", sessionId, po.requestId());
            return null;
        }
        Map<String, Object> frame = new HashMap<>();
        frame.put("type", "order_action");
        frame.put("action", "create");
        frame.put("requestId", po.requestId());
        frame.put("productId", po.productId());
        frame.put("productName", po.productName());
        frame.put("quantity", po.quantity());
        frame.put("unitPrice", po.unitPrice());
        frame.put("totalAmount", po.totalAmount());
        recordEvent(po.sessionId(), po.userId(), "CREATE", po.requestId(), null,
                po.productId(), po.productName(), po.quantity(), po.totalAmount(), "DISPATCHED", null);
        return frame;
    }

    /** 取消 pending 单（未派发的前端动作随 pending 一并作废——前端收不到帧就不会执行） */
    public void cancel(String sessionId) {
        PendingOrderStore.PendingOrder po = pendingStore.get(sessionId);
        if (po != null) {
            redis.delete(DISPATCHED_KEY + po.requestId());
        }
        pendingStore.remove(sessionId);
    }

    /**
     * 取消"刚才成交的那一单"：发取消动作帧给前端代执行（/shopOrder/cancel）。
     * requestId 复用 lastOrderNo 维度做幂等（重复说"取消"只派发一次）。
     *
     * @return 动作帧；没有可取消的订单或已派发时返回 null
     */
    public Map<String, Object> cancelLastOrder(String sessionId) {
        String orderNo = lastOrderNo(sessionId);
        if (orderNo == null || orderNo.isBlank()) return null;
        String requestId = "cancel-" + orderNo;
        Boolean first = redis.opsForValue()
                .setIfAbsent(DISPATCHED_KEY + requestId, "1", Duration.ofMinutes(15));
        if (!Boolean.TRUE.equals(first)) return null;
        Map<String, Object> frame = new HashMap<>();
        frame.put("type", "order_action");
        frame.put("action", "cancel");
        frame.put("requestId", requestId);
        frame.put("orderNo", orderNo);
        // 归因事件：userId 取该单原成交事件的记录（同一会话同一用户）
        Long userId = orderEventRepo.findFirstByOrderNoAndStatus(orderNo, "SUCCEEDED")
                .map(com.mindcart.voice.entity.VoiceOrderEventEntity::getUserId).orElse(null);
        recordEvent(sessionId, userId, "CANCEL", requestId, orderNo, null, null, null, null, "DISPATCHED", null);
        return frame;
    }

    /** 归因事件写入（旁路，失败不影响交易链路） */
    private void recordEvent(String sessionId, Long userId, String action, String requestId, String orderNo,
                             Long productId, String productName, Integer quantity,
                             BigDecimal totalAmount, String status, String failReason) {
        try {
            var e = new com.mindcart.voice.entity.VoiceOrderEventEntity();
            e.setSessionId(sessionId);
            e.setUserId(userId == null ? 0L : userId);
            e.setAction(action);
            e.setRequestId(requestId);
            e.setOrderNo(orderNo);
            e.setProductId(productId);
            e.setProductName(productName);
            e.setQuantity(quantity);
            e.setTotalAmount(totalAmount);
            e.setStatus(status);
            e.setFailReason(failReason);
            orderEventRepo.save(e);
        } catch (Exception ex) {
            log.warn("[VoiceOrder] 归因事件写入失败（不影响交易）requestId={}: {}", requestId, ex.getMessage());
        }
    }

    /** 成交/失败回执更新归因事件状态 */
    private void updateEvent(String requestId, String status, String orderNo, String failReason) {
        try {
            orderEventRepo.findByRequestId(requestId).ifPresent(e -> {
                e.setStatus(status);
                if (orderNo != null) e.setOrderNo(orderNo);
                if (failReason != null) e.setFailReason(failReason.length() > 200
                        ? failReason.substring(0, 200) : failReason);
                orderEventRepo.save(e);
            });
        } catch (Exception ex) {
            log.warn("[VoiceOrder] 归因事件更新失败 requestId={}: {}", requestId, ex.getMessage());
        }
    }

    /**
     * 前端代执行结果回传收口。
     *
     * @return 口播文案；requestId 与 pending 不匹配（过期/重复回传）返回 null
     */
    public String completeOnResult(String sessionId, String requestId,
                                   boolean success, String orderNo, String error) {
        // 取消链路：requestId = cancel-{orderNo}，无 pending 单参与
        if (requestId != null && requestId.startsWith("cancel-")) {
            redis.delete(DISPATCHED_KEY + requestId);
            updateEvent(requestId, success ? "SUCCEEDED" : "FAILED", null, error);
            if (success) {
                String no = requestId.substring("cancel-".length());
                if (no.equals(lastOrderNo(sessionId))) {
                    redis.delete(LAST_ORDER_KEY + sessionId);
                }
                return "订单已取消，退款会原路退回。还有什么想看的吗？";
            }
            return "取消没成功" + (error == null || error.isBlank() ? "。" : "（" + error + "）。")
                    + "订单还在，你可以到订单页再操作。";
        }
        PendingOrderStore.PendingOrder po = pendingStore.get(sessionId);
        if (po == null || !po.requestId().equals(requestId)) {
            log.warn("[VoiceOrder] 过期或不匹配的 order_result sessionId={} requestId={}", sessionId, requestId);
            return null;
        }
        pendingStore.remove(sessionId);
        redis.delete(DISPATCHED_KEY + requestId);
        updateEvent(requestId, success ? "SUCCEEDED" : "FAILED", orderNo, error);
        if (success) {
            redis.opsForValue().set(LAST_ORDER_KEY + sessionId, orderNo, Duration.ofMinutes(30));
            // MindCart 单号形如 OD20260922024650764367：前 6 位是日期前缀，口播要的是**末 6 位**随机串
            return String.format("下单成功，订单尾号 %s。还有想看的吗？",
                    orderNo != null && orderNo.length() >= 6
                            ? orderNo.substring(orderNo.length() - 6) : orderNo);
        }
        return "下单没成功" + (error == null || error.isBlank() ? "，" : "（" + error + "），")
                + "这张单我先给你留着，要再试一次还是换款看看？";
    }

    public String lastOrderNo(String sessionId) {
        return redis.opsForValue().get(LAST_ORDER_KEY + sessionId);
    }
}
