package com.mindcart.ai.service;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.mindcart.ai.entity.BusinessToolRequest;
import com.mindcart.ai.entity.BusinessToolResult;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.Result;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.goods.api.ProductVO;
import com.mindcart.trade.api.OrderBriefVO;
import com.mindcart.trade.api.OrderFeignClient;
import com.mindcart.trade.api.OrderStatsVO;
import com.mindcart.user.api.UserFeignClient;
import com.mindcart.user.api.UserVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 业务工具（用户画像/相似商品/订单状态）：跨服务数据经 Feign 实时获取。
 */
@Service
public class BusinessToolService {

    @Resource
    private UserFeignClient userClient;
    @Resource
    private OrderFeignClient orderClient;
    @Resource
    private ProductToolService productToolService;

    public BusinessToolResult queryUserProfile(BusinessToolRequest request) {
        UserVO user = resolveUser(request);
        BusinessToolResult result = new BusinessToolResult();
        result.setToolCode("USER_PROFILE_QUERY");
        result.setUserId(user.getId());
        result.setUsername(user.getUsername());
        result.setUserName(user.getName());
        result.setPhone(user.getPhone());
        result.setEmail(user.getEmail());
        result.setBalance(user.getBalance());
        OrderStatsVO stats = unwrap(orderClient.statsOfUser(user.getId()));
        if (stats != null) {
            result.setOrderCount(stats.getOrderCount());
            result.setOrderAmount(stats.getPaidAmount());
            result.setLatestOrderNo(stats.getLatestOrderNo());
            result.setLatestOrderStatus(stats.getLatestOrderStatus());
        }
        result.setMessage("用户画像查询成功");
        return result;
    }

    public BusinessToolResult querySimilarProducts(BusinessToolRequest request) {
        ProductVO anchor = unwrapAnchor(request);
        BusinessToolResult result = new BusinessToolResult();
        result.setToolCode("SIMILAR_PRODUCT_QUERY");
        result.setProductId(anchor.getId());
        result.setProductNo(anchor.getProductNo());
        result.setProductName(anchor.getName());
        result.setCategoryId(anchor.getCategoryId());
        result.setBrandId(anchor.getBrandId());
        java.util.List<ProductVO> similar = productToolService.similarProducts(
                anchor.getCategoryId(), anchor.getBrandId(), anchor.getId(), 5);
        result.setSimilarProducts(similar);
        result.setMessage("相似商品查询成功，共 " + result.getSimilarProducts().size() + " 个");
        return result;
    }

    public BusinessToolResult queryOrderStatus(BusinessToolRequest request) {
        if (ObjectUtil.isEmpty(request) || (ObjectUtil.isEmpty(request.getOrderId())
                && StrUtil.isBlank(request.getOrderNo()))) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        OrderBriefVO order = unwrap(orderClient.resolveOrder(request.getOrderId(), request.getOrderNo()));
        if (order == null) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "订单不存在");
        }
        BusinessToolResult result = new BusinessToolResult();
        result.setToolCode("ORDER_STATUS_QUERY");
        result.setUserId(order.getUserId());
        result.setOrderId(order.getId());
        result.setOrderNo(order.getOrderNo());
        result.setOrderStatus(order.getStatus());
        result.setTotalAmount(order.getTotalAmount());
        result.setTotalQuantity(order.getTotalQuantity());
        result.setReceiverName(order.getReceiverName());
        result.setReceiverPhone(order.getReceiverPhone());
        result.setReceiverAddress(order.getReceiverAddress());
        result.setPayTime(order.getPayTime());
        result.setShipTime(order.getShipTime());
        result.setFinishTime(order.getFinishTime());
        result.setCancelTime(order.getCancelTime());
        result.setMessage("订单当前状态：" + order.getStatus());
        return result;
    }

    private UserVO resolveUser(BusinessToolRequest request) {
        if (ObjectUtil.isEmpty(request)) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        UserVO user = null;
        if (request.getUserId() != null) {
            user = unwrap(userClient.getById(request.getUserId()));
        } else if (StrUtil.isNotBlank(request.getUsername())) {
            user = unwrap(userClient.getByUsername(request.getUsername()));
        }
        if (user == null) {
            throw new CustomException(ResultCodeEnum.USER_NOT_EXIST_ERROR);
        }
        return user;
    }

    @Resource
    private com.mindcart.goods.api.GoodsFeignClient goodsClient;

    private ProductVO unwrapAnchor(BusinessToolRequest request) {
        if (ObjectUtil.isEmpty(request)) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        ProductVO product = unwrap(goodsClient.resolveProduct(
                request.getProductId(), null, request.getProductName()));
        if (product == null) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "商品不存在");
        }
        return product;
    }

    private <T> T unwrap(Result<T> result) {
        if (result == null || !ResultCodeEnum.SUCCESS.getCode().equals(result.getCode())) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR,
                    StrUtil.blankToDefault(result == null ? null : result.getMsg(), "下游服务不可用"));
        }
        return result.getData();
    }
}
