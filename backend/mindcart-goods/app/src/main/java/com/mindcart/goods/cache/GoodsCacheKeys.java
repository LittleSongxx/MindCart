package com.mindcart.goods.cache;

import cn.hutool.core.util.StrUtil;
import com.mindcart.goods.entity.Product;
import com.mindcart.goods.entity.ProductBrand;
import com.mindcart.goods.entity.ProductCategory;

/**
 * 缓存键构造与会否缓存的判定。
 *
 * 单独放一个类、由 SpEL 通过 {@code T(...)} 调用，是为了让「写缓存的地方」和
 * 「主动失效的地方」共用同一个键格式——键格式分两处手写极易漂移，漂移后失效会静默失败
 * （看起来清了缓存，实际上另一个键还在被读）。
 *
 * 是否缓存的判定（{@link #cacheable(Product)}）刻意排除带关键词的查询：
 * 搜索词的取值空间是无界的，缓存它既低命中又会让 Redis 里堆积大量一次性键。
 */
public final class GoodsCacheKeys {

    private GoodsCacheKeys() {
    }

    /**
     * 商品查询的缓存键：指定 id 时按 id 建键（可被库存变动精确失效），
     * 否则按过滤条件建键（同一组筛选条件才复用）。
     */
    public static String productKey(Product condition) {
        if (condition == null) {
            return "all";
        }
        if (condition.getId() != null) {
            return byId(condition.getId());
        }
        StringBuilder key = new StringBuilder("list");
        append(key, "c", condition.getCategoryId());
        append(key, "b", condition.getBrandId());
        append(key, "s", condition.getStatus());
        append(key, "p", condition.getPrice());
        append(key, "r", condition.getIsRecommend());
        return key.toString();
    }

    /** 单品键的唯一构造入口：@Cacheable 的 SpEL 与库存失效都走这里 */
    public static String byId(Integer productId) {
        return "id=" + productId;
    }

    /** 带关键词（名称/编号模糊匹配）的查询不缓存 */
    public static boolean cacheable(Product condition) {
        return condition == null
                || (StrUtil.isBlank(condition.getName()) && StrUtil.isBlank(condition.getProductNo()));
    }

    public static String categoryKey(ProductCategory condition) {
        return "list:name=" + StrUtil.nullToEmpty(condition == null ? null : condition.getName())
                + ":enabled=" + (condition == null ? "" : condition.getIsEnabled());
    }

    public static String brandKey(ProductBrand condition) {
        return "list:name=" + StrUtil.nullToEmpty(condition == null ? null : condition.getName())
                + ":enabled=" + (condition == null ? "" : condition.getIsEnabled());
    }

    private static void append(StringBuilder key, String name, Object value) {
        if (value != null) {
            key.append(':').append(name).append('=').append(value);
        }
    }
}
