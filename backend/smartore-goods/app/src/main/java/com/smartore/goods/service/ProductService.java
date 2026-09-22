package com.smartore.goods.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.cache.GoodsCacheNames;
import com.smartore.goods.entity.Product;
import com.smartore.common.exception.CustomException;
import com.smartore.goods.mapper.ProductMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {

    @Resource
    private ProductMapper productMapper;
    @Resource
    private com.smartore.goods.mapper.StockMapper stockMapper;

    /** 商品与库存权威表双写必须在同一本地事务：中途崩溃会出现"无 stock 行"的商品，之后扣减永远失败 */
    @Transactional
    @CacheEvict(value = GoodsCacheNames.PRODUCT, allEntries = true)
    public void add(Product product) {
        validate(product);
        if (ObjectUtil.isEmpty(product.getProductNo())) {
            product.setProductNo("SP" + DateUtil.format(DateUtil.date(), "yyyyMMddHHmmssSSS"));
        }
        Product dbProduct = productMapper.selectByProductNo(product.getProductNo());
        if (ObjectUtil.isNotNull(dbProduct)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        setDefaultValue(product);
        String now = DateUtil.now();
        product.setCreateTime(now);
        product.setUpdateTime(now);
        productMapper.insert(product);
        // 库存权威表与商品展示列同事务对齐（新增时以 stock_quantity 初始化）
        stockMapper.upsert(product.getId(), product.getStockQuantity() == null ? 0 : product.getStockQuantity());
    }

    @Transactional
    @CacheEvict(value = GoodsCacheNames.PRODUCT, allEntries = true)
    public void updateById(Product product) {
        if (ObjectUtil.isEmpty(product.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        validate(product);
        Product dbProduct = productMapper.selectByProductNo(product.getProductNo());
        if (ObjectUtil.isNotNull(dbProduct) && !dbProduct.getId().equals(product.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        setDefaultValue(product);
        product.setUpdateTime(DateUtil.now());
        productMapper.updateById(product);
        // 管理端改库存 = 显式重置权威库存（其余情况展示列由库存变动流水驱动同步）
        if (product.getStockQuantity() != null) {
            stockMapper.upsert(product.getId(), product.getStockQuantity());
        }
    }

    @Transactional
    @CacheEvict(value = GoodsCacheNames.PRODUCT, allEntries = true)
    public void deleteById(Integer id) {
        productMapper.deleteById(id);
        // 同步清理库存权威行：留着会变成孤儿行，商品 id 复用时还会被 upsert 命中而"继承"旧库存
        stockMapper.deleteByProductId(id);
    }

    @Transactional
    @CacheEvict(value = GoodsCacheNames.PRODUCT, allEntries = true)
    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            deleteById(id);
        }
    }

    /**
     * 商品查询缓存（含"/product/selectAll?id=x"这种单品读取，键为 id=N）。
     *
     * 为什么带关键词就不缓存：搜索词取值空间无界，缓存它既低命中又会堆大量一次性键。
     * 为什么这里的库存展示值可以是陈旧的：下单的权威校验是 stock 表上的条件扣减
     * （deductAvailable 的 available >= quantity），与展示列无关，因此不会超卖；
     * 而库存真的变动时 StockSagaService 会精确失效 id=N 这个键，预检路径（按 id 读）因此保持新鲜，
     * 列表页则容忍 TTL 内的展示延迟。
     */
    @Cacheable(value = GoodsCacheNames.PRODUCT,
            key = "T(com.smartore.goods.cache.GoodsCacheKeys).productKey(#product)",
            condition = "T(com.smartore.goods.cache.GoodsCacheKeys).cacheable(#product)",
            sync = true)
    public List<Product> selectAll(Product product) {
        return productMapper.selectAll(product);
    }

    /** 管理端分页列表：条件与分页组合多样、命中率低，不进缓存（分页参数由 PageHelper 接管） */
    public PageInfo<Product> selectPage(Product product, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Product> list = productMapper.selectAll(product);
        return PageInfo.of(list);
    }

    private void validate(Product product) {
        if (ObjectUtil.isEmpty(product.getName())
                || ObjectUtil.isEmpty(product.getCategoryId())
                || ObjectUtil.isEmpty(product.getBrandId())
                || product.getPrice() == null
                || product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }

    private void setDefaultValue(Product product) {
        if (product.getOriginalPrice() == null) {
            product.setOriginalPrice(product.getPrice());
        }
        if (product.getIsRecommend() == null) {
            product.setIsRecommend(0);
        }
        if (ObjectUtil.isEmpty(product.getStatus())) {
            product.setStatus("ON_SALE");
        }
        if (product.getSort() == null) {
            product.setSort(1);
        }
    }
}
