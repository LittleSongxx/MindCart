package com.smartore.trade.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.smartore.common.context.UserContext;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.Result;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import com.smartore.trade.entity.ShoppingCart;
import com.smartore.trade.mapper.ShoppingCartMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车。购物车表只存 userId+productId+quantity+selected；
 * 名称/价格/图片/库存等展示信息每次经 goods 服务取实时快照 —— 价格不再过期，展示与下单校验同一份数据。
 */
@Service
public class ShoppingCartService {

    @Resource
    private ShoppingCartMapper shoppingCartMapper;
    @Resource
    private GoodsFeignClient goodsClient;

    /** 单行数量上限：防恶意超大数量行撑爆下单校验/库存流水 */
    private static final int MAX_QUANTITY_PER_LINE = 99;

    public void add(ShoppingCart cart) {
        Integer userId = UserContext.requireUserId();
        if (cart.getProductId() == null || cart.getQuantity() == null || cart.getQuantity() <= 0) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        if (cart.getQuantity() > MAX_QUANTITY_PER_LINE) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "单个商品最多加购 " + MAX_QUANTITY_PER_LINE + " 件");
        }
        ProductVO product = unwrap(goodsClient.getProduct(cart.getProductId()));
        if (product == null || !"ON_SALE".equals(product.getStatus())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "商品不存在或已下架");
        }
        ShoppingCart dbCart = shoppingCartMapper.selectByUserIdAndProductId(userId, cart.getProductId());
        if (ObjectUtil.isNotNull(dbCart)) {
            dbCart.setQuantity(dbCart.getQuantity() + cart.getQuantity());
            dbCart.setSelected(1);
            dbCart.setUpdateTime(DateUtil.now());
            shoppingCartMapper.updateById(dbCart);
            return;
        }
        cart.setUserId(userId);
        cart.setSelected(ObjectUtil.isEmpty(cart.getSelected()) ? 1 : cart.getSelected());
        String now = DateUtil.now();
        cart.setCreateTime(now);
        cart.setUpdateTime(now);
        shoppingCartMapper.insert(cart);
    }

    public void updateById(ShoppingCart cart) {
        if (ObjectUtil.isEmpty(cart.getId()) || ObjectUtil.isEmpty(cart.getQuantity()) || cart.getQuantity() <= 0) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        requireOwned(cart.getId());
        cart.setUpdateTime(DateUtil.now());
        shoppingCartMapper.updateById(cart);
    }

    public void deleteById(Integer id) {
        requireOwned(id);
        shoppingCartMapper.deleteById(id);
    }

    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            deleteById(id);
        }
    }

    /** 我的购物车（实时商品快照） */
    public List<ShoppingCart> selectMine() {
        Integer userId = UserContext.requireUserId();
        List<ShoppingCart> carts = shoppingCartMapper.selectByUserId(userId);
        if (carts.isEmpty()) {
            return carts;
        }
        Map<Integer, ProductVO> productMap = unwrap(goodsClient.getProducts(
                carts.stream().map(ShoppingCart::getProductId).toList())).stream()
                .collect(Collectors.toMap(ProductVO::getId, Function.identity()));
        for (ShoppingCart cart : carts) {
            ProductVO product = productMap.get(cart.getProductId());
            if (product != null) {
                cart.setProductName(product.getName());
                cart.setProductNo(product.getProductNo());
                cart.setCoverImage(product.getCoverImage());
                cart.setPrice(product.getPrice());
                cart.setStatus(product.getStatus());
                cart.setStockQuantity(product.getStockQuantity());
            }
        }
        return carts;
    }

    private void requireOwned(Integer cartId) {
        if (shoppingCartMapper.countOwned(UserContext.requireUserId(), cartId) == 0) {
            throw new CustomException(ResultCodeEnum.FORBIDDEN);
        }
    }

    private <T> T unwrap(Result<T> result) {
        if (result == null || !ResultCodeEnum.SUCCESS.getCode().equals(result.getCode())) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR,
                    StrUtil.blankToDefault(result == null ? null : result.getMsg(), "商品服务不可用"));
        }
        return result.getData();
    }
}
