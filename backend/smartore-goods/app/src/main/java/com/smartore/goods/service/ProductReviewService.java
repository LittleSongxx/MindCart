package com.smartore.goods.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.entity.ProductReview;
import com.smartore.common.exception.CustomException;
import com.smartore.goods.mapper.ProductReviewMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductReviewService {

    @Resource
    private ProductReviewMapper productReviewMapper;
    @Resource
    private com.smartore.trade.api.OrderFeignClient orderClient;
    @Resource
    private com.smartore.user.api.UserFeignClient userClient;

    /** 用户名批量回填（跨库 JOIN 拆除后的服务层替代，一次 Feign 而非逐行） */
    private void fillUserNames(List<ProductReview> reviews) {
        List<Integer> ids = reviews.stream().map(ProductReview::getUserId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        try {
            var result = userClient.getUsers(ids);
            if (result != null && "200".equals(result.getCode()) && result.getData() != null) {
                Map<Integer, String> names = result.getData().stream().collect(Collectors.toMap(
                        com.smartore.user.api.UserVO::getId,
                        u -> u.getName() == null ? u.getUsername() : u.getName()));
                reviews.forEach(r -> {
                    String name = names.get(r.getUserId());
                    if (name != null) {
                        r.setUserName(name);
                    }
                });
            }
        } catch (Exception ignored) {
            // 展示名缺失不阻塞评价列表
        }
    }
public void add(ProductReview productReview) {
        validate(productReview);
        // 购买资格校验（该用户完成过含此商品的订单）——订单在交易域，经 Feign 查询
        com.smartore.common.result.Result<Boolean> owned =
                orderClient.ownsProduct(productReview.getUserId(), productReview.getProductId());
        if (owned == null || !Boolean.TRUE.equals(owned.getData())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "只有完成过该商品订单的用户才能评价");
        }
        ProductReview dbReview = productReviewMapper.selectByOrderItemId(productReview.getOrderItemId());
        if (ObjectUtil.isNotNull(dbReview)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "该订单行已评价过");
        }
        productReview.setAuditStatus("APPROVED");
        String now = DateUtil.now();
        productReview.setCreateTime(now);
        productReview.setUpdateTime(now);
        productReviewMapper.insert(productReview);
    }

    public void audit(ProductReview productReview) {
        if (ObjectUtil.isEmpty(productReview.getId()) || ObjectUtil.isEmpty(productReview.getAuditStatus())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        productReview.setUpdateTime(DateUtil.now());
        productReviewMapper.updateById(productReview);
    }

    public List<ProductReview> selectAll(ProductReview productReview) {
        List<ProductReview> list = productReviewMapper.selectAll(productReview);
        fillUserNames(list);
        return list;
    }

    public PageInfo<ProductReview> selectPage(ProductReview productReview, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<ProductReview> list = productReviewMapper.selectAll(productReview);
        fillUserNames(list);
        return PageInfo.of(list);
    }

    private void validate(ProductReview productReview) {
        if (ObjectUtil.isEmpty(productReview.getUserId())
                || ObjectUtil.isEmpty(productReview.getOrderId())
                || ObjectUtil.isEmpty(productReview.getOrderItemId())
                || ObjectUtil.isEmpty(productReview.getProductId())
                || ObjectUtil.isEmpty(productReview.getContent())
                || productReview.getRating() == null
                || productReview.getRating() < 1
                || productReview.getRating() > 5) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
