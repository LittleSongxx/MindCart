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

@Service
public class ProductReviewService {

    @Resource
    private ProductReviewMapper productReviewMapper;
    @Resource
    private com.smartore.trade.api.OrderFeignClient orderClient;
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
        return productReviewMapper.selectAll(productReview);
    }

    public PageInfo<ProductReview> selectPage(ProductReview productReview, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<ProductReview> list = productReviewMapper.selectAll(productReview);
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
