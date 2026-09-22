package com.smartore.trade.service;

import com.smartore.common.context.UserContext;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.Result;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import com.smartore.goods.api.StockOpRequest;
import com.smartore.trade.entity.OrderCreateRequest;
import com.smartore.trade.entity.ShopOrder;
import com.smartore.user.api.UserFeignClient;
import com.smartore.user.api.WalletStatusVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单 Saga 的行为契约（真实 MySQL，仅把两个下游 Feign 客户端换成替身）。
 *
 * 为什么必须测这三个失败分支：它们决定"钱和库存会不会对不上"，而且分支之间的差异很细——
 * 「下游明确拒绝」要立刻补偿，「超时这种模糊失败」绝不能补偿（服务端事务可能仍在飞行），
 * 「已扣款但后续失败」更不能判死。写错任何一条都是资损，光靠读代码看不出来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "smartore.internal-token=test-internal-token",
                "smartore.gateway-token=test-gateway-token",
                "smartore.cache.enabled=false"
        })
@Testcontainers
class OrderSagaIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    private static final int USER_ID = 7;
    private static final int PRODUCT_ID = 8801;

    @MockitoBean
    private UserFeignClient userClient;
    @MockitoBean
    private GoodsFeignClient goodsClient;
    /** 替掉 broker：Outbox 中继的投递确认在测试里同步完成，既不连 Rabbit 也不等 5s 确认超时 */
    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderSagaService orderSagaService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger requestSeq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        UserContext.set(USER_ID, "USER");
        jdbcTemplate.update("delete from shop_order_item");
        jdbcTemplate.update("delete from shop_order");
        jdbcTemplate.update("delete from shopping_cart");
        jdbcTemplate.update("delete from order_request_idempotency");
        jdbcTemplate.update("delete from trade_event_ledger");

        // 下游替身：商品在售、有货；钱包默认扣款成功
        when(goodsClient.getProducts(any())).thenReturn(Result.success(List.of(product(10))));
        when(goodsClient.deductForOrder(any())).thenReturn(Result.success(true));
        when(goodsClient.restoreForOrder(any())).thenReturn(Result.success(true));
        when(userClient.payForOrder(any())).thenReturn(Result.success(true));
        when(userClient.platformIncome(any())).thenReturn(Result.success(true));
        when(userClient.refundToUser(any())).thenReturn(Result.success(true));
        when(userClient.platformRefundOut(any())).thenReturn(Result.success(true));
        when(userClient.walletStatus(anyString())).thenReturn(Result.success(wallet(false)));

        // broker 替身：convertAndSend 立即回 ack，让 publishOne 走成功路径
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ProductVO product(int stock) {
        ProductVO vo = new ProductVO();
        vo.setId(PRODUCT_ID);
        vo.setProductNo("SP-SAGA-" + PRODUCT_ID);
        vo.setName("Saga 测试商品");
        vo.setPrice(new BigDecimal("1999.00"));
        vo.setStockQuantity(stock);
        vo.setStatus("ON_SALE");
        return vo;
    }

    private WalletStatusVO wallet(boolean paid) {
        WalletStatusVO status = new WalletStatusVO();
        status.setPaid(paid);
        status.setIncome(paid);
        return status;
    }

    /** 购物车放一件选中商品，模拟前端"提交订单" */
    private void givenSelectedCartItem() {
        jdbcTemplate.update("insert into shopping_cart (user_id, product_id, quantity, selected, create_time, update_time) "
                + "values (?, ?, 1, 1, now(), now())", USER_ID, PRODUCT_ID);
    }

    private OrderCreateRequest request(String requestId) {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setRequestId(requestId);
        request.setReceiverName("张三");
        request.setReceiverPhone("18800009999");
        request.setReceiverAddress("文三路 168 号");
        return request;
    }

    private Map<String, Object> orderOf(String orderNo) {
        return jdbcTemplate.queryForMap("select * from shop_order where order_no = ?", orderNo);
    }

    private String newRequestId() {
        return "saga-" + System.nanoTime() + "-" + requestSeq.incrementAndGet();
    }

    /** 1. 正常下单：订单 PAID、Outbox 账本恰好一条、幂等行绑定 SUCCESS */
    @Test
    void happyPathClosesOrderAsPaidWithOneLedgerEvent() {
        givenSelectedCartItem();
        ShopOrder order = orderSagaService.create(request(newRequestId()));

        assertEquals("PAID", order.getStatus());
        assertEquals(new BigDecimal("1999.00"), order.getTotalAmount());
        assertEquals(1, order.getItems().size());

        assertEquals("PAID", orderOf(order.getOrderNo()).get("status"));
        // 不断言 publish_status：中继任务每 3s 会把 PENDING 推进为 PUBLISHED，断言状态会闪断
        Integer ledger = jdbcTemplate.queryForObject(
                "select count(*) from trade_event_ledger where order_no = ?", Integer.class, order.getOrderNo());
        assertEquals(1, ledger, "一次成功下单应恰好产生一条订单支付事件");
        assertEquals("SUCCESS", jdbcTemplate.queryForObject(
                "select status from order_request_idempotency where order_no = ?", String.class, order.getOrderNo()));
        // 下单会清空已选中的购物车行
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "select count(*) from shopping_cart where user_id = ?", Integer.class, USER_ID));
    }

    /** 2. 同 requestId 重放：返回原单，不产生第二单、不重复扣款 */
    @Test
    void replayedRequestReturnsOriginalOrderWithoutSideEffects() {
        givenSelectedCartItem();
        String requestId = newRequestId();
        ShopOrder first = orderSagaService.create(request(requestId));

        ShopOrder replay = orderSagaService.create(request(requestId));

        assertEquals(first.getOrderNo(), replay.getOrderNo());
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "select count(*) from shop_order", Integer.class), "重放不应创建第二个订单");
        verify(userClient, org.mockito.Mockito.times(1)).payForOrder(any());
    }

    /** 3. 下游明确拒绝（余额不足）：立刻回补库存并判失败 */
    @Test
    void deterministicRejectionCompensatesStockAndMarksPayFailed() {
        givenSelectedCartItem();
        when(userClient.payForOrder(any())).thenThrow(
                new CustomException(ResultCodeEnum.BALANCE_NOT_ENOUGH));

        assertThrows(CustomException.class, () -> orderSagaService.create(request(newRequestId())));

        String orderNo = jdbcTemplate.queryForObject(
                "select order_no from shop_order order by id desc limit 1", String.class);
        assertEquals("PAY_FAILED", orderOf(orderNo).get("status"));
        verify(goodsClient).restoreForOrder(any());
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "select status from order_request_idempotency where order_no = ?", String.class, orderNo));
    }

    /**
     * 4. 模糊失败（下游超时）：订单必须停在 PAYING 交给恢复任务，绝不能补偿。
     * 超时意味着服务端事务可能仍在飞行，此刻回补库存会撞上"迟到落地的扣款" → 资错。
     */
    @Test
    void ambiguousTimeoutKeepsOrderPendingAndDoesNotCompensate() {
        givenSelectedCartItem();
        when(userClient.payForOrder(any())).thenThrow(new RuntimeException("Read timed out"));

        assertThrows(CustomException.class, () -> orderSagaService.create(request(newRequestId())));

        String orderNo = jdbcTemplate.queryForObject(
                "select order_no from shop_order order by id desc limit 1", String.class);
        assertEquals("PAYING", orderOf(orderNo).get("status"), "模糊失败不能判死，要留给恢复任务");
        verify(goodsClient, never()).restoreForOrder(any());
    }

    /** 5. 已扣款但后续步骤失败：同样保持 PAYING，不留"已收款却判失败"的资错 */
    @Test
    void paidButLaterStepFailedKeepsOrderPending() {
        givenSelectedCartItem();
        when(userClient.walletStatus(anyString())).thenReturn(Result.success(wallet(true)));
        when(userClient.platformIncome(any())).thenThrow(new RuntimeException("platform income failed"));

        assertThrows(RuntimeException.class, () -> orderSagaService.create(request(newRequestId())));

        String orderNo = jdbcTemplate.queryForObject(
                "select order_no from shop_order order by id desc limit 1", String.class);
        assertEquals("PAYING", orderOf(orderNo).get("status"), "已扣款时不得补偿，交给恢复任务续走成功路径");
        verify(goodsClient, never()).restoreForOrder(any());
    }

    /** 6. 恢复任务：停滞订单按钱包凭据续走成功；确无扣款才回补并判失败 */
    @Test
    void recoveryJobResolvesStaleOrdersByWalletEvidence() {
        String paidOrderNo = insertStaleOrder("PAYING");
        String unpaidOrderNo = insertStaleOrder("PAYING");
        when(userClient.walletStatus(eq(paidOrderNo))).thenReturn(Result.success(wallet(true)));
        when(userClient.walletStatus(eq(unpaidOrderNo))).thenReturn(Result.success(wallet(false)));

        orderSagaService.recoverStaleOrders();

        assertEquals("PAID", orderOf(paidOrderNo).get("status"), "有扣款凭据 → 续走成功路径");
        assertEquals("PAY_FAILED", orderOf(unpaidOrderNo).get("status"), "停滞 60s 仍无扣款 → 才可判失败");
        verify(goodsClient).restoreForOrder(any());
    }

    /** 7. 资错自愈：PAY_FAILED 却查到扣款流水 → 自动反向退款并收口 CANCELLED */
    @Test
    void recoveryJobSelfHealsLateCaptureOnFailedOrder() {
        String orderNo = insertStaleOrder("PAY_FAILED");
        when(userClient.walletStatus(eq(orderNo))).thenReturn(Result.success(wallet(true)));

        orderSagaService.recoverStaleOrders();

        assertEquals("CANCELLED", orderOf(orderNo).get("status"));
        verify(userClient).refundToUser(any());
    }

    /**
     * 直插一条"停滞"订单：把 update_time 拨回 120s 前。
     * 显式赋值优先于 ON UPDATE CURRENT_TIMESTAMP，因此这里能构造出停滞状态。
     */
    private String insertStaleOrder(String status) {
        String orderNo = "OD-STALE-" + System.nanoTime();
        jdbcTemplate.update("insert into shop_order (order_no, user_id, total_amount, total_quantity, status, "
                        + "receiver_name, receiver_phone, receiver_address, create_time, update_time) "
                        + "values (?, ?, 1999.00, 1, ?, '张三', '18800009999', '文三路 168 号', now(), now())",
                orderNo, USER_ID, status);
        jdbcTemplate.update("update shop_order set update_time = date_sub(now(), interval 120 second) "
                + "where order_no = ?", orderNo);
        return orderNo;
    }

    /** 库存扣减请求里带的商品必须与购物车一致，这里顺带固定断言口径 */
    @Test
    void deductRequestCarriesOrderNoAndItems() {
        givenSelectedCartItem();
        ShopOrder order = orderSagaService.create(request(newRequestId()));

        org.mockito.ArgumentCaptor<StockOpRequest> captor =
                org.mockito.ArgumentCaptor.forClass(StockOpRequest.class);
        verify(goodsClient).deductForOrder(captor.capture());
        assertEquals(order.getOrderNo(), captor.getValue().getOrderNo(),
                "扣库存必须以订单号做幂等键");
        assertEquals(PRODUCT_ID, captor.getValue().getItems().get(0).getProductId());
        assertEquals(1, captor.getValue().getItems().get(0).getQuantity());
        assertNotNull(order.getOrderNo());
        assertTrue(order.getOrderNo().startsWith("OD"));
    }

    /** 下单前校验：商品下架时不进入本地事务，直接以业务错误拒绝 */
    @Test
    void offlineProductIsRejectedBeforeCreatingOrder() {
        givenSelectedCartItem();
        ProductVO offline = product(10);
        offline.setStatus("OFF_SALE");
        when(goodsClient.getProducts(any())).thenReturn(Result.success(List.of(offline)));

        CustomException e = assertThrows(CustomException.class,
                () -> orderSagaService.create(request(newRequestId())));

        assertEquals(ResultCodeEnum.PARAM_ERROR.getCode(), e.getCode());
        assertTrue(e.getMsg().contains("已下架"));
        assertEquals(0, (int) jdbcTemplate.queryForObject("select count(*) from shop_order", Integer.class),
                "预检失败不应留下订单");
    }

    /** 购物车为空时的拒绝路径（避免空单进 Saga） */
    @Test
    void emptySelectedCartIsRejected() {
        CustomException e = assertThrows(CustomException.class,
                () -> orderSagaService.create(request(newRequestId())));
        assertEquals(ResultCodeEnum.PARAM_ERROR.getCode(), e.getCode());
        assertTrue(e.getMsg().contains("没有选中"));
    }

    /** 幂等键格式：缺失 requestId 时在校验层就被拒（DTO 注解之外服务层也守一道） */
    @Test
    void missingRequestIdIsRejected() {
        givenSelectedCartItem();
        CustomException e = assertThrows(CustomException.class,
                () -> orderSagaService.create(request("   ")));
        assertEquals(ResultCodeEnum.PARAM_LOST_ERROR.getCode(), e.getCode());
    }

    /** 同 requestId 不同内容：必须报冲突而不是当成重放返回原单 */
    @Test
    void sameRequestIdWithDifferentContentConflicts() {
        givenSelectedCartItem();
        String requestId = newRequestId();
        orderSagaService.create(request(requestId));

        jdbcTemplate.update("insert into shopping_cart (user_id, product_id, quantity, selected, create_time, update_time) "
                + "values (?, ?, 1, 1, now(), now())", USER_ID, PRODUCT_ID);
        OrderCreateRequest different = request(requestId);
        different.setReceiverName("李四");

        CustomException e = assertThrows(CustomException.class, () -> orderSagaService.create(different));
        assertEquals(ResultCodeEnum.CONFLICT.getCode(), e.getCode());
    }
}
