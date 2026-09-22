package com.mindcart.goods.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.goods.entity.ProductFavorite;
import com.mindcart.goods.entity.Product;
import com.mindcart.common.exception.CustomException;
import com.mindcart.goods.mapper.ProductFavoriteMapper;
import com.mindcart.goods.mapper.ProductMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductFavoriteService {

    @Resource
    private ProductFavoriteMapper productFavoriteMapper;
    @Resource
    private ProductMapper productMapper;

    public void add(ProductFavorite productFavorite) {
        // 身份只认当前登录用户（防替他人塞收藏）
        productFavorite.setUserId(com.mindcart.common.context.UserContext.requireUserId());
        validate(productFavorite);
        Product product = productMapper.selectById(productFavorite.getProductId());
        if (ObjectUtil.isNull(product)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        ProductFavorite dbFavorite = productFavoriteMapper.selectByUserIdAndProductId(productFavorite.getUserId(), productFavorite.getProductId());
        if (ObjectUtil.isNotNull(dbFavorite)) {
            return;
        }
        productFavorite.setCreateTime(DateUtil.now());
        productFavoriteMapper.insert(productFavorite);
    }

    public void deleteById(Integer id) {
        requireOwned(id);
        productFavoriteMapper.deleteById(id);
    }

    public List<ProductFavorite> selectAll(ProductFavorite productFavorite) {
        // 普通用户只能看自己的收藏；管理员按条件（可查全站）
        if (!com.mindcart.common.context.UserContext.isAdmin()) {
            productFavorite.setUserId(com.mindcart.common.context.UserContext.requireUserId());
        }
        return productFavoriteMapper.selectAll(productFavorite);
    }

    private void requireOwned(Integer id) {
        ProductFavorite favorite = productFavoriteMapper.selectById(id);
        if (favorite == null || !favorite.getUserId().equals(
                com.mindcart.common.context.UserContext.requireUserId())) {
            throw new CustomException(ResultCodeEnum.FORBIDDEN);
        }
    }

    private void validate(ProductFavorite productFavorite) {
        if (ObjectUtil.isEmpty(productFavorite.getUserId()) || ObjectUtil.isEmpty(productFavorite.getProductId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
