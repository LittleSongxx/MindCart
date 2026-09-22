package com.mindcart.goods.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.goods.entity.ProductReview;
import com.mindcart.common.exception.CustomException;
import com.mindcart.goods.mapper.ProductReviewMapper;
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
    private com.mindcart.trade.api.OrderFeignClient orderClient;
    @Resource
    private com.mindcart.user.api.UserFeignClient userClient;

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
                        com.mindcart.user.api.UserVO::getId,
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
        // 身份只认网关注入的当前用户，请求体里的 userId 一律丢弃（防冒名评价）
        productReview.setUserId(com.mindcart.common.context.UserContext.requireUserId());
        validate(productReview);
        // 购买资格校验（精确到订单行）：该用户名下已完成订单的该行确实买了这个商品
        com.mindcart.common.result.Result<Boolean> owned = orderClient.ownsOrderItem(
                productReview.getUserId(), productReview.getOrderItemId(), productReview.getProductId());
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
        // 网关 RBAC 已限 ADMIN；服务侧再校验一次，直连调用也过不去
        if (!com.mindcart.common.context.UserContext.isAdmin()) {
            throw new CustomException(ResultCodeEnum.FORBIDDEN);
        }
        productReview.setUpdateTime(DateUtil.now());
        productReviewMapper.updateById(productReview);
    }

    public List<ProductReview> selectAll(ProductReview productReview) {
        visibleCondition(productReview);
        List<ProductReview> list = productReviewMapper.selectAll(productReview);
        fillUserNames(list);
        return list;
    }

    public PageInfo<ProductReview> selectPage(ProductReview productReview, Integer pageNum, Integer pageSize) {
        visibleCondition(productReview);
        PageHelper.startPage(pageNum, pageSize);
        List<ProductReview> list = productReviewMapper.selectAll(productReview);
        fillUserNames(list);
        return PageInfo.of(list);
    }

    /** 普通用户只能看到已过审评价（REJECTED/PENDING 不外泄）；管理员按条件全量 */
    private void visibleCondition(ProductReview productReview) {
        if (!com.mindcart.common.context.UserContext.isAdmin()) {
            productReview.setAuditStatus("APPROVED");
        }
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
