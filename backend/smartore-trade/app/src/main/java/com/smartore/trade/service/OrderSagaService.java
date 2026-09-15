package com.smartore.trade.service;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.smartore.common.context.UserContext;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.Result;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.common.util.BizNoGenerator;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import com.smartore.goods.api.StockOpRequest;
import com.smartore.trade.entity.OrderCreateRequest;
import com.smartore.trade.entity.OrderRequestIdempotency;
import com.smartore.trade.entity.ShopOrder;
import com.smartore.trade.entity.ShopOrderItem;
import com.smartore.trade.entity.ShoppingCart;
import com.smartore.trade.mapper.OrderRequestIdempotencyMapper;
import com.smartore.trade.mapper.ShopOrderItemMapper;
import com.smartore.trade.mapper.ShopOrderMapper;
import com.smartore.trade.mapper.ShoppingCartMapper;
import com.smartore.user.api.UserFeignClient;
import com.smartore.user.api.WalletOpRequest;
import com.smartore.user.api.WalletStatusVO;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 下单/取消的 Saga 编排器（ADR-002：幂等步骤 + Outbox + 恢复任务，替代 Seata 类协调器）。
 *
 * 下单：本地事务①(订单PAYING+明细+清车+幂等占位) → goods 扣库存 → user 扣款 → user 平台收款
 *      → 本地事务②(markPaid+Outbox+幂等绑定)；失败→补偿（回补库存/退款，凭据前置+幂等）→markPayFailed。
 * 崩溃恢复：PAYING 超时订单由恢复任务按钱包凭据判定 —— 已扣款则续走成功路径，未扣款则回补库存并判失败。
 * 所有远程步骤以 orderNo 幂等：重试/重放即收敛，无全局锁、无协调器单点。
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    /** PAYING/CANCELLING 超过该秒数仍未收敛的订单，交给恢复任务处理 */
    private static final int STALE_SECONDS = 60;

    @Resource
    private ShopOrderMapper shopOrderMapper;
    @Resource
    private ShopOrderItemMapper shopOrderItemMapper;
    @Resource
    private ShoppingCartMapper shoppingCartMapper;
    @Resource
    private OrderRequestIdempotencyMapper idempotencyMapper;
    @Resource
    private GoodsFeignClient goodsClient;
    @Resource
    private UserFeignClient userClient;
    @Resource
    private OrderTxService orderTxService;

    // ==================== 下单 ====================

    public ShopOrder create(OrderCreateRequest request) {
        Integer userId = UserContext.requireUserId();
        if (StrUtil.isBlank(request.getRequestId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR, "缺少 requestId（幂等键）");
        }
        // ---- 幂等闸门 ----
        String requestHash = hashOf(userId, request);
        OrderRequestIdempotency existing = idempotencyMapper.selectByRequestId(request.getRequestId());
        if (existing != null) {
            if (existing.getOrderId() != null) {
                if (!requestHash.equals(existing.getRequestHash())) {
                    throw new CustomException(ResultCodeEnum.CONFLICT, "requestId 已被不同内容的请求占用");
                }
                return assemble(shopOrderMapper.selectById(existing.getOrderId()));
            }
            if ("FAILED".equals(existing.getStatus())) {
                throw new CustomException(ResultCodeEnum.PARAM_ERROR, "上次提交已失败，请重新发起下单");
            }
            throw new CustomException(ResultCodeEnum.CONFLICT, "相同 requestId 的请求正在处理中，请稍后查询结果");
        }
        request.setUserId(userId);

        // ---- 购物车选中项 + 商品现价/库存校验（跨服务重新定价） ----
        List<ShoppingCart> cartList = shoppingCartMapper.selectByUserId(userId).stream()
                .filter(c -> c.getSelected() != null && c.getSelected() == 1)
                .toList();
        if (cartList.isEmpty()) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "购物车没有选中的商品");
        }
        Map<Integer, ProductVO> productMap = unwrap(goodsClient.getProducts(
                cartList.stream().map(ShoppingCart::getProductId).toList())).stream()
                .collect(Collectors.toMap(ProductVO::getId, Function.identity()));
        for (ShoppingCart cart : cartList) {
            ProductVO product = productMap.get(cart.getProductId());
            if (product == null || !"ON_SALE".equals(product.getStatus())) {
                throw new CustomException(ResultCodeEnum.PARAM_ERROR,
                        "商品已下架或不存在（productId=" + cart.getProductId() + "）");
            }
            if (product.getStockQuantity() == null || product.getStockQuantity() < cart.getQuantity()) {
                throw new CustomException(ResultCodeEnum.STOCK_NOT_ENOUGH);
            }
        }

        ShopOrder order = new ShopOrder();
        order.setOrderNo(BizNoGenerator.next("OD"));
        order.setUserId(userId);
        order.setStatus("PAYING");
        order.setReceiverName(request.getReceiverName());
        order.setReceiverPhone(request.getReceiverPhone());
        order.setReceiverAddress(request.getReceiverAddress());

        List<ShopOrderItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalQuantity = 0;
        for (ShoppingCart cart : cartList) {
            ProductVO product = productMap.get(cart.getProductId());
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity()));
            totalAmount = totalAmount.add(subtotal);
            totalQuantity += cart.getQuantity();
            ShopOrderItem item = new ShopOrderItem();
            item.setProductId(product.getId());
            item.setProductNo(product.getProductNo());
            item.setProductName(product.getName());
            item.setCoverImage(product.getCoverImage());
            item.setPrice(product.getPrice());
            item.setQuantity(cart.getQuantity());
            item.setSubtotalAmount(subtotal);
            items.add(item);
        }
        order.setTotalAmount(totalAmount);
        order.setTotalQuantity(totalQuantity);

        // ---- 本地事务① → 远程步骤 → 收口 ----
        orderTxService.persistOrder(order, items, cartList, request.getRequestId(), requestHash);
        try {
            unwrap(goodsClient.deductForOrder(stockRequest(order.getOrderNo(), items)));
            unwrap(userClient.payForOrder(walletOpOf(order, totalAmount, "订单支付")));
            unwrap(userClient.platformIncome(walletOpOf(order, totalAmount, "订单收入")));
            orderTxService.finishPay(order, items);
            return assemble(shopOrderMapper.selectById(order.getId()));
        } catch (Exception e) {
            handlePayFailure(order, items, e);
            throw translate(e);
        }
    }

    /**
     * 失败处理：查钱包凭据决定策略。
     * 未扣款 → 补偿（回补库存）+ markPayFailed（退款是凭据前置的空操作）。
     * 已扣款 → 不做破坏性补偿，留给恢复任务按证据续走成功路径（防止"已收款却判失败"的资错）。
     */
    private void handlePayFailure(ShopOrder order, List<ShopOrderItem> items, Exception cause) {
        boolean paid;
        try {
            paid = unwrap(userClient.walletStatus(order.getOrderNo())).isPaid();
        } catch (Exception probeError) {
            log.error("钱包凭据查询失败，保持 PAYING 交恢复任务（orderNo={}）：{}", order.getOrderNo(), probeError.getMessage());
            return;
        }
        if (paid) {
            log.warn("买家已扣款但后续步骤失败，交恢复任务续走成功路径（orderNo={}）：{}", order.getOrderNo(), cause.getMessage());
            return;
        }
        try {
            unwrap(goodsClient.restoreForOrder(stockRequest(order.getOrderNo(), items)));
        } catch (Exception e) {
            log.error("补偿回补库存失败，恢复任务将重试（orderNo={}）：{}", order.getOrderNo(), e.getMessage());
            return;
        }
        orderTxService.finishPayFailed(order);
    }

    // ==================== 取消 ====================

    public ShopOrder cancel(Integer orderId) {
        ShopOrder order = requireOrder(orderId);
        requireOwnerOrAdmin(order);
        if ("CANCELLED".equals(order.getStatus())) {
            return assemble(order); // 幂等重放
        }
        if (!"PAID".equals(order.getStatus())) {
            throw new CustomException(ResultCodeEnum.ORDER_STATUS_ERROR);
        }
        // 原子占位：PAID→CANCELLING，并发取消只有一个进入补偿
        shopOrderMapper.beginCancel(order.getId());
        runCancelSteps(order);
        return assemble(shopOrderMapper.selectById(order.getId()));
    }

    /** 取消补偿步骤（全幂等），失败保持 CANCELLING 由恢复任务重试 */
    private void runCancelSteps(ShopOrder order) {
        List<ShopOrderItem> items = shopOrderItemMapper.selectByOrderId(order.getId());
        try {
            unwrap(userClient.refundToUser(walletOpOf(order, order.getTotalAmount(), "订单取消退款")));
            unwrap(userClient.platformRefundOut(walletOpOf(order, order.getTotalAmount(), "订单取消退款支出")));
            unwrap(goodsClient.restoreForOrder(stockRequest(order.getOrderNo(), items)));
        } catch (Exception e) {
            log.error("取消补偿步骤失败，保持 CANCELLING 由恢复任务重试（orderNo={}）：{}",
                    order.getOrderNo(), e.getMessage());
            throw translate(e);
        }
        orderTxService.finishCancel(order, items);
    }

    // ==================== 恢复任务（Saga 收敛器） ====================

    @Scheduled(fixedDelay = 15000)
    public void recoverStaleOrders() {
        for (ShopOrder order : shopOrderMapper.selectStalePaying(STALE_SECONDS)) {
            log.warn("恢复任务处理 PAYING 订单：{}", order.getOrderNo());
            try {
                List<ShopOrderItem> items = shopOrderItemMapper.selectByOrderId(order.getId());
                WalletStatusVO wallet = unwrap(userClient.walletStatus(order.getOrderNo()));
                if (wallet.isPaid()) {
                    unwrap(userClient.platformIncome(walletOpOf(order, order.getTotalAmount(), "订单收入")));
                    orderTxService.finishPay(order, items);
                } else {
                    unwrap(goodsClient.restoreForOrder(stockRequest(order.getOrderNo(), items)));
                    orderTxService.finishPayFailed(order);
                }
            } catch (Exception e) {
                log.error("恢复任务处理失败，下轮重试（orderNo={}）：{}", order.getOrderNo(), e.getMessage());
            }
        }
        for (ShopOrder order : shopOrderMapper.selectStaleCancelling(STALE_SECONDS)) {
            log.warn("恢复任务处理 CANCELLING 订单：{}", order.getOrderNo());
            try {
                runCancelSteps(order);
            } catch (Exception e) {
                log.error("取消恢复失败，下轮重试（orderNo={}）：{}", order.getOrderNo(), e.getMessage());
            }
        }
    }

    // ==================== 工具 ====================

    private WalletOpRequest walletOpOf(ShopOrder order, BigDecimal amount, String remark) {
        WalletOpRequest request = new WalletOpRequest();
        request.setOrderNo(order.getOrderNo());
        request.setUserId(order.getUserId());
        request.setAmount(amount);
        request.setRemark(remark);
        return request;
    }

    private StockOpRequest stockRequest(String orderNo, List<ShopOrderItem> items) {
        StockOpRequest request = new StockOpRequest();
        request.setOrderNo(orderNo);
        request.setItems(items.stream().map(item -> {
            StockOpRequest.StockItem stockItem = new StockOpRequest.StockItem();
            stockItem.setProductId(item.getProductId());
            stockItem.setQuantity(item.getQuantity());
            return stockItem;
        }).toList());
        return request;
    }

    private String hashOf(Integer userId, OrderCreateRequest request) {
        String raw = userId + "|" + StrUtil.nullToEmpty(request.getReceiverName())
                + "|" + StrUtil.nullToEmpty(request.getReceiverPhone())
                + "|" + StrUtil.nullToEmpty(request.getReceiverAddress());
        return SecureUtil.sha256(raw);
    }

    private ShopOrder requireOrder(Integer id) {
        if (ObjectUtil.isEmpty(id)) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        ShopOrder order = shopOrderMapper.selectById(id);
        if (order == null) {
            throw new CustomException(ResultCodeEnum.ORDER_NOT_EXIST_ERROR);
        }
        return order;
    }

    private void requireOwnerOrAdmin(ShopOrder order) {
        if (!UserContext.isAdmin() && !order.getUserId().equals(UserContext.requireUserId())) {
            throw new CustomException(ResultCodeEnum.FORBIDDEN);
        }
    }

    /** Feign 返回解包：业务错误码透传为业务异常 */
    private <T> T unwrap(Result<T> result) {
        if (result == null) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR);
        }
        if (!ResultCodeEnum.SUCCESS.getCode().equals(result.getCode())) {
            throw new CustomException(result.getCode(), StrUtil.blankToDefault(result.getMsg(), "下游服务调用失败"));
        }
        return result.getData();
    }

    private RuntimeException translate(Exception e) {
        if (e instanceof CustomException custom) {
            return custom;
        }
        String message = StrUtil.blankToDefault(e.getMessage(), "下单失败");
        if (message.contains("余额不足")) {
            return new CustomException(ResultCodeEnum.BALANCE_NOT_ENOUGH);
        }
        if (message.contains("库存不足")) {
            return new CustomException(ResultCodeEnum.STOCK_NOT_ENOUGH);
        }
        return new CustomException(ResultCodeEnum.PARAM_ERROR, message);
    }

    private ShopOrder assemble(ShopOrder order) {
        if (order != null) {
            order.setItems(shopOrderItemMapper.selectByOrderId(order.getId()));
        }
        return order;
    }
}
