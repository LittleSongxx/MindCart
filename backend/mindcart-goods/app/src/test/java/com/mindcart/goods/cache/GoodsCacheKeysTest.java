package com.mindcart.goods.cache;

import com.mindcart.goods.entity.Product;
import com.mindcart.goods.entity.ProductBrand;
import com.mindcart.goods.entity.ProductCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存键口径的回归防线。
 *
 * 缓存最危险的失效模式不是"没生效"而是"静默失效"：写缓存的键与主动失效的键
 * 一旦格式漂移，失效会看起来执行成功、实际清的是另一个键，线上表现为"改了价不刷新"。
 * 因此这里锁住：① 单键格式唯一（@Cacheable 与库存失效共用 byId）；
 * ② 不同筛选条件不共用键；③ 带关键词的查询不进缓存。
 */
class GoodsCacheKeysTest {

    @Test
    void byIdKeyIsStableAndUsedByEveryIdReadPath() {
        assertEquals("id=42", GoodsCacheKeys.byId(42));

        Product condition = new Product();
        condition.setId(42);
        // 指定 id 时走单键（可被库存变动精确失效），而不是退化成条件组合键
        assertEquals(GoodsCacheKeys.byId(42), GoodsCacheKeys.productKey(condition));
    }

    @Test
    void differentFiltersDoNotShareAKey() {
        Product byCategory = new Product();
        byCategory.setCategoryId(2);
        byCategory.setStatus("ON_SALE");

        Product byBrand = new Product();
        byBrand.setBrandId(3);
        byBrand.setStatus("ON_SALE");

        Product sameAsFirst = new Product();
        sameAsFirst.setCategoryId(2);
        sameAsFirst.setStatus("ON_SALE");

        assertNotEquals(GoodsCacheKeys.productKey(byCategory), GoodsCacheKeys.productKey(byBrand));
        assertEquals(GoodsCacheKeys.productKey(byCategory), GoodsCacheKeys.productKey(sameAsFirst),
                "同样的筛选条件必须命中同一个键");
    }

    @Test
    void idKeyWinsOverOtherFiltersSoStockEvictionStaysPrecise() {
        Product withIdAndOtherFilters = new Product();
        withIdAndOtherFilters.setId(7);
        withIdAndOtherFilters.setStatus("ON_SALE");
        // 只要带了 id 就按 id 建键：库存失效只清 id=N，不会因为多了个筛选条件就漏清
        assertEquals("id=7", GoodsCacheKeys.productKey(withIdAndOtherFilters));
    }

    @Test
    void keywordQueriesAreNotCached() {
        Product byName = new Product();
        byName.setName("笔记本");
        assertFalse(GoodsCacheKeys.cacheable(byName), "按名称搜索的取值空间无界，不应进缓存");

        Product byProductNo = new Product();
        byProductNo.setProductNo("SP20260101");
        assertFalse(GoodsCacheKeys.cacheable(byProductNo), "按编号搜索同理");

        Product byFilter = new Product();
        byFilter.setCategoryId(1);
        byFilter.setPrice(new BigDecimal("8000"));
        assertTrue(GoodsCacheKeys.cacheable(byFilter));

        assertTrue(GoodsCacheKeys.cacheable(null), "无条件查询是最热的一次调用，必须可缓存");
        assertEquals("all", GoodsCacheKeys.productKey(null));
    }

    @Test
    void dictionaryKeysDistinguishEnabledFlag() {
        ProductCategory enabled = new ProductCategory();
        enabled.setIsEnabled(1);
        ProductCategory all = new ProductCategory();
        assertNotEquals(GoodsCacheKeys.categoryKey(enabled), GoodsCacheKeys.categoryKey(all),
                "前台查启用分类与管理端查全量不能共用一份缓存");

        ProductBrand brand = new ProductBrand();
        brand.setIsEnabled(1);
        assertTrue(GoodsCacheKeys.brandKey(brand).contains("enabled=1"));
    }
}
