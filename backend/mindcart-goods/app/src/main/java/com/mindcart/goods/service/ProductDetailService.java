package com.mindcart.goods.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.goods.cache.GoodsCacheNames;
import com.mindcart.goods.entity.ProductDetail;
import com.mindcart.common.exception.CustomException;
import com.mindcart.goods.mapper.ProductDetailMapper;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductDetailService {

    @Resource
    private ProductDetailMapper productDetailMapper;

    /**
     * 读后写（存在则更新、不存在则插入）必须在一个事务里：
     * 并发两次"首次保存"会在唯一键 idx_product_detail_product_id 上撞车，
     * 无事务时前一次已经提交的插入不会被回滚，表现为一个 409 之外还留下半写状态。
     */
    @Transactional
    @CacheEvict(value = GoodsCacheNames.PRODUCT_DETAIL, key = "#productDetail.productId")
    public void save(ProductDetail productDetail) {
        validate(productDetail);
        ProductDetail dbDetail = productDetailMapper.selectByProductId(productDetail.getProductId());
        if (ObjectUtil.isNull(dbDetail)) {
            String now = DateUtil.now();
            productDetail.setCreateTime(now);
            productDetail.setUpdateTime(now);
            productDetailMapper.insert(productDetail);
        } else {
            productDetail.setId(dbDetail.getId());
            productDetail.setUpdateTime(DateUtil.now());
            productDetailMapper.updateById(productDetail);
        }
    }

    /**
     * 详情含长文本且一商品一条，命中率高、变更少；
     * null 也会被缓存（防穿透）——查无详情的商品不会被反复回源。
     */
    @Cacheable(value = GoodsCacheNames.PRODUCT_DETAIL, key = "#productId")
    public ProductDetail selectByProductId(Integer productId) {
        if (ObjectUtil.isEmpty(productId)) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        return productDetailMapper.selectByProductId(productId);
    }

    private void validate(ProductDetail productDetail) {
        if (ObjectUtil.isEmpty(productDetail.getProductId())
                || ObjectUtil.isEmpty(productDetail.getDetailContent())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
