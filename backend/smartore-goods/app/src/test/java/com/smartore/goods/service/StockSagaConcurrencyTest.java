package com.smartore.goods.service;

import com.smartore.goods.api.StockOpRequest;
import com.smartore.goods.mapper.StockMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发防超卖的行为契约（真实 MySQL，Testcontainers）：
 * 库存 10，20 个并发扣减请求各扣 1 —— 恰好 10 个成功、库存精确归零、不透支。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                // UserContextFilter 启动期强制双令牌非空（生产由 dev.sh bootstrap 注入），测试给占位值
                "smartore.internal-token=test-internal-token",
                "smartore.gateway-token=test-gateway-token"
        })
@Testcontainers
class StockSagaConcurrencyTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private StockSagaService stockSagaService;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private void givenProduct(int id) {
        // stock.product_id 有外键，先落一行最小商品
        jdbcTemplate.update("""
                insert ignore into product
                (id, product_no, name, category_id, brand_id, price, original_price, stock_quantity, status, sort, create_time, update_time)
                values (?, ?, ?, 1, 1, 9.90, 9.90, 0, 'ON_SALE', 1, now(), now())
                """, id, "SPIT" + id, "测试商品" + id);
    }

    @Test
    void concurrentDeductNeverOversells() throws Exception {
        int productId = 10086;
        givenProduct(productId);
        stockMapper.upsert(productId, 10);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            String orderNo = "IT-CONC-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        stockSagaService.deductForOrder(request(orderNo, productId, 1));
                        success.incrementAndGet();
                    } catch (Exception expectedOnExhaust) {
                        // 库存不足属预期失败
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发扣减未在时限内完成");
        pool.shutdown();

        assertEquals(10, success.get(), "恰好 10 个扣减成功");
        assertEquals(0, stockMapper.selectByProductId(productId).getAvailable(), "库存精确归零且无透支");
    }

    @Test
    void deductIsIdempotentPerOrder() {
        int productId = 10087;
        givenProduct(productId);
        stockMapper.upsert(productId, 5);
        StockOpRequest request = request("IT-IDEM-1", productId, 2);

        stockSagaService.deductForOrder(request);
        stockSagaService.deductForOrder(request); // 同单重放

        assertEquals(3, stockMapper.selectByProductId(productId).getAvailable(), "同单重放只扣一次");

        stockSagaService.restoreForOrder(restore("IT-IDEM-1", productId, 2));
        stockSagaService.restoreForOrder(restore("IT-IDEM-1", productId, 2)); // 同单重放

        assertEquals(5, stockMapper.selectByProductId(productId).getAvailable(), "回补幂等只加一次");
    }

    private StockOpRequest request(String orderNo, int productId, int qty) {
        StockOpRequest request = new StockOpRequest();
        request.setOrderNo(orderNo);
        StockOpRequest.StockItem item = new StockOpRequest.StockItem();
        item.setProductId(productId);
        item.setQuantity(qty);
        request.setItems(List.of(item));
        return request;
    }

    private StockOpRequest restore(String orderNo, int productId, int qty) {
        return request(orderNo, productId, qty);
    }
}
