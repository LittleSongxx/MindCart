package com.mindcart.goods.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.goods.cache.GoodsCacheNames;
import com.mindcart.goods.entity.ProductCategory;
import com.mindcart.common.exception.CustomException;
import com.mindcart.goods.mapper.ProductCategoryMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductCategoryService {

    @Resource
    private ProductCategoryMapper productCategoryMapper;

    /**
     * 分类写操作同时清商品缓存：商品列表行里带 category_name（join product_category 得来），
     * 分类改名不清商品缓存会出现"列表还显示旧分类名"的不一致。
     */
    @CacheEvict(value = {GoodsCacheNames.CATEGORY, GoodsCacheNames.PRODUCT}, allEntries = true)
    public void add(ProductCategory productCategory) {
        validate(productCategory);
        if (productCategory.getSort() == null) {
            productCategory.setSort(1);
        }
        if (productCategory.getIsEnabled() == null) {
            productCategory.setIsEnabled(1);
        }
        String now = DateUtil.now();
        productCategory.setCreateTime(now);
        productCategory.setUpdateTime(now);
        productCategoryMapper.insert(productCategory);
    }

    @CacheEvict(value = {GoodsCacheNames.CATEGORY, GoodsCacheNames.PRODUCT}, allEntries = true)
    public void updateById(ProductCategory productCategory) {
        if (ObjectUtil.isEmpty(productCategory.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        validate(productCategory);
        productCategory.setUpdateTime(DateUtil.now());
        productCategoryMapper.updateById(productCategory);
    }

    @CacheEvict(value = {GoodsCacheNames.CATEGORY, GoodsCacheNames.PRODUCT}, allEntries = true)
    public void deleteById(Integer id) {
        productCategoryMapper.deleteById(id);
    }

    @CacheEvict(value = {GoodsCacheNames.CATEGORY, GoodsCacheNames.PRODUCT}, allEntries = true)
    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            deleteById(id);
        }
    }

    /** 分类字典小而稳定（前台每次进首页都要拉），是最划算的缓存对象 */
    @Cacheable(value = GoodsCacheNames.CATEGORY,
            key = "T(com.mindcart.goods.cache.GoodsCacheKeys).categoryKey(#productCategory)",
            sync = true)
    public List<ProductCategory> selectAll(ProductCategory productCategory) {
        return productCategoryMapper.selectAll(productCategory);
    }

    public PageInfo<ProductCategory> selectPage(ProductCategory productCategory, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<ProductCategory> list = productCategoryMapper.selectAll(productCategory);
        return PageInfo.of(list);
    }

    private void validate(ProductCategory productCategory) {
        if (ObjectUtil.isEmpty(productCategory.getName())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
