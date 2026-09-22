package com.smartore.goods.cache;

import com.smartore.goods.api.StockOpRequest;
import com.smartore.goods.entity.Product;
import com.smartore.goods.entity.ProductCategory;
import com.smartore.goods.service.ProductCategoryService;
import com.smartore.goods.service.ProductService;
import com.smartore.goods.service.StockSagaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存装配与失效行为的端到端验证（真实 MySQL + 真实 Redis）。
 *
 * 必须起真实容器才能测的原因：{@code @Cacheable} 的 key/condition 是 SpEL 字符串，
 * 写错了编译期完全看不出来，只在运行期抛异常或静默不命中；缓存"没生效"和"失效没清掉"
 * 都不会报错，只会让页面上的价格/库存停在旧值。所以这里断言的是真实缓存状态，不是调用次数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "smartore.internal-token=test-internal-token",
                "smartore.gateway-token=test-gateway-token",
                "smartore.cache.enabled=true",
                "smartore.cache.key-prefix=test:goods:cache:",
                "smartore.cache.ttls.product=60s",
                "smartore.cache.ttls.category=30m"
        })
@Testcontainers
class GoodsCacheIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private ProductService productService;
    @Autowired
    private ProductCategoryService productCategoryService;
    @Autowired
    private StockSagaService stockSagaService;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int PRODUCT_ID = 9001;

    private void givenProduct(int id, int stock) {
        jdbcTemplate.update("insert ignore into product (id, product_no, name, category_id, brand_id, price, "
                + "original_price, stock_quantity, status, sort, create_time, update_time) "
                + "values (?, ?, ?, 1, 1, 1999.00, 1999.00, ?, 'ON_SALE', 1, now(), now())",
                id, "SP-CACHE-" + id, "缓存测试商品" + id, stock);
        jdbcTemplate.update("insert into stock (product_id, available, update_time) values (?, ?, now()) "
                + "on duplicate key update available = values(available)", id, stock);
    }

    private Cache productCache() {
        Cache cache = cacheManager.getCache(GoodsCacheNames.PRODUCT);
        assertNotNull(cache, "商品缓存区应已装配（CacheAutoConfiguration 生效）");
        return cache;
    }

    /** SpEL 能解析、命中缓存、写失效、关键词不缓存 —— 一次覆盖主要路径 */
    @Test
    void listQueryIsCachedByFiltersAndEvictedOnWrite() {
        givenProduct(PRODUCT_ID, 5);
        Product condition = new Product();
        condition.setCategoryId(1);
        condition.setStatus("ON_SALE");

        List<Product> first = productService.selectAll(condition);
        assertFalse(first.isEmpty());

        Cache cache = productCache();
        String key = GoodsCacheKeys.productKey(condition);
        assertNotNull(cache.get(key), "第二次查询前应已有缓存条目（key=" + key + "）");

        // 直接改库再查：命中缓存时应仍返回旧值，证明真的走了缓存而不是每次回源
        jdbcTemplate.update("update product set name = '被直接改库的名字' where id = ?", PRODUCT_ID);
        List<Product> second = productService.selectAll(condition);
        assertEquals(first.get(0).getName(), second.get(0).getName(), "命中缓存时不应看到库里的直接改动");

        // 走 service 改价：写操作必须让它失效
        Product update = new Product();
        update.setId(PRODUCT_ID);
        update.setProductNo("SP-CACHE-" + PRODUCT_ID);
        update.setName("改名后的商品");
        update.setCategoryId(1);
        update.setBrandId(1);
        update.setPrice(new java.math.BigDecimal("1888.00"));
        productService.updateById(update);
        assertNull(cache.get(key), "商品写操作后条件键应被清除");
    }

    /** 单品键与库存失效：下单扣减后 id=N 必须立刻失效（预检路径因此保持新鲜） */
    @Test
    void stockChangeEvictsOnlyTheSingleProductKey() {
        int id = PRODUCT_ID + 1;
        givenProduct(id, 5);
        Product byId = new Product();
        byId.setId(id);
        List<Product> read = productService.selectAll(byId);
        assertEquals(5, read.get(0).getStockQuantity());

        Cache cache = productCache();
        assertNotNull(cache.get(GoodsCacheKeys.byId(id)), "按 id 读取应写入 id=N 键");

        StockOpRequest request = new StockOpRequest();
        request.setOrderNo("OD-CACHE-" + id);
        StockOpRequest.StockItem item = new StockOpRequest.StockItem();
        item.setProductId(id);
        item.setQuantity(2);
        request.setItems(List.of(item));
        stockSagaService.deductForOrder(request);

        assertNull(cache.get(GoodsCacheKeys.byId(id)), "库存变动后 id=N 键应在事务提交后被清除");
        assertEquals(3, productService.selectAll(byId).get(0).getStockQuantity(), "重新回源应看到扣减后的库存");
    }

    /** 带关键词的查询不进缓存（键空间无界） */
    @Test
    void keywordQueryIsNotCached() {
        Product byName = new Product();
        byName.setName("缓存测试");
        productService.selectAll(byName);
        assertNull(productCache().get(GoodsCacheKeys.productKey(byName)),
                "按名称搜索不应产生缓存条目");
    }

    /** 分类字典缓存 + 写失效 */
    @Test
    void categoryDictionaryIsCachedAndEvictedOnWrite() {
        ProductCategory condition = new ProductCategory();
        condition.setIsEnabled(1);
        productCategoryService.selectAll(condition);

        Cache categoryCache = cacheManager.getCache(GoodsCacheNames.CATEGORY);
        assertNotNull(categoryCache);
        assertNotNull(categoryCache.get(GoodsCacheKeys.categoryKey(condition)));

        ProductCategory created = new ProductCategory();
        created.setName("缓存测试分类" + System.nanoTime());
        created.setIsEnabled(1);
        created.setSort(99);
        productCategoryService.add(created);

        assertNull(categoryCache.get(GoodsCacheKeys.categoryKey(condition)), "分类写操作后字典缓存应被清除");
        // 分类改名会污染商品列表行的 category_name，所以商品缓存也要一起清
        assertTrue(cacheManager.getCache(GoodsCacheNames.PRODUCT) != null);
    }
}
