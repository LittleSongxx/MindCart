package com.mindcart.goods.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.goods.cache.GoodsCacheNames;
import com.mindcart.goods.entity.ProductBrand;
import com.mindcart.common.exception.CustomException;
import com.mindcart.goods.mapper.ProductBrandMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductBrandService {

    @Resource
    private ProductBrandMapper productBrandMapper;

    /** 品牌写操作同时清商品缓存：商品列表行里带 brand_name（join product_brand 得来） */
    @CacheEvict(value = {GoodsCacheNames.BRAND, GoodsCacheNames.PRODUCT}, allEntries = true)
    public void add(ProductBrand productBrand) {
        validate(productBrand);
        if (productBrand.getSort() == null) {
            productBrand.setSort(1);
        }
        if (productBrand.getIsEnabled() == null) {
            productBrand.setIsEnabled(1);
        }
        String now = DateUtil.now();
        productBrand.setCreateTime(now);
        productBrand.setUpdateTime(now);
        productBrandMapper.insert(productBrand);
    }

    @CacheEvict(value = {GoodsCacheNames.BRAND, GoodsCacheNames.PRODUCT}, allEntries = true)
    public void updateById(ProductBrand productBrand) {
        if (ObjectUtil.isEmpty(productBrand.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        validate(productBrand);
        productBrand.setUpdateTime(DateUtil.now());
        productBrandMapper.updateById(productBrand);
    }

    @CacheEvict(value = {GoodsCacheNames.BRAND, GoodsCacheNames.PRODUCT}, allEntries = true)
    public void deleteById(Integer id) {
        productBrandMapper.deleteById(id);
    }

    @CacheEvict(value = {GoodsCacheNames.BRAND, GoodsCacheNames.PRODUCT}, allEntries = true)
    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            productBrandMapper.deleteById(id);
        }
    }

    /** 品牌字典与分类同理：小而稳定、前台每次进首页都要拉 */
    @Cacheable(value = GoodsCacheNames.BRAND,
            key = "T(com.mindcart.goods.cache.GoodsCacheKeys).brandKey(#productBrand)",
            sync = true)
    public List<ProductBrand> selectAll(ProductBrand productBrand) {
        return productBrandMapper.selectAll(productBrand);
    }

    public PageInfo<ProductBrand> selectPage(ProductBrand productBrand, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<ProductBrand> list = productBrandMapper.selectAll(productBrand);
        return PageInfo.of(list);
    }

    private void validate(ProductBrand productBrand) {
        if (ObjectUtil.isEmpty(productBrand.getName())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
