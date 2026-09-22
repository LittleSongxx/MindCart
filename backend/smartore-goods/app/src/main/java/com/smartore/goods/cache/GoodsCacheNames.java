package com.smartore.goods.cache;

/**
 * 商品域缓存区名。与 application.yml 里 {@code smartore.cache.ttls} 的键一一对应，
 * 改名字要同步改两处（TTL 配错不会报错，只会静默用默认值）。
 */
public final class GoodsCacheNames {

    /** 商品列表/单品查询（键由 GoodsCacheKeys 从查询条件派生），TTL 见 yml */
    public static final String PRODUCT = "product";

    /** 商品详情（长文本，按 productId 一商品一条） */
    public static final String PRODUCT_DETAIL = "detail";

    /** 商品参数（按 productId 聚合） */
    public static final String PRODUCT_PARAM = "param";

    public static final String CATEGORY = "category";

    public static final String BRAND = "brand";

    private GoodsCacheNames() {
    }
}
