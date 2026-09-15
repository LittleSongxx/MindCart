package com.smartore.trade.service;

import cn.hutool.core.util.ObjectUtil;
import com.smartore.common.context.UserContext;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.trade.entity.ShopOrder;
import com.smartore.trade.mapper.ShopOrderItemMapper;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.trade.entity.ShopOrderItem;
import com.smartore.trade.mapper.ShopOrderMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单查询与状态推进（发货/确认收货）。下单/取消的 Saga 编排在 OrderSagaService。
 */
@Service
public class ShopOrderService {

    @Resource
    private ShopOrderMapper shopOrderMapper;
    @Resource
    private ShopOrderItemMapper shopOrderItemMapper;
    @Resource
    private OrderSagaService orderSagaService;
    @Resource
    private GoodsFeignClient goodsClient;

    /** 组装订单时回填"是否已评价"标记（评价数据在商品域，经内部接口批量查） */
    private void fillReviewed(List<ShopOrder> orders) {
        List<Integer> itemIds = orders.stream()
                .flatMap(o -> o.getItems() == null ? java.util.stream.Stream.<ShopOrderItem>empty() : o.getItems().stream())
                .map(ShopOrderItem::getId).toList();
        if (itemIds.isEmpty()) {
            return;
        }
        try {
            var result = goodsClient.reviewedItemIds(itemIds.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")));
            if (result != null && "200".equals(result.getCode()) && result.getData() != null) {
                var reviewed = new java.util.HashSet<>(result.getData());
                for (ShopOrder order : orders) {
                    if (order.getItems() != null) {
                        order.getItems().forEach(item -> item.setReviewed(reviewed.contains(item.getId()) ? 1 : 0));
                    }
                }
            }
        } catch (Exception e) {
            // 评价标记属增强信息，查询失败不阻塞订单展示
        }
    }

    public List<ShopOrder> selectAll(ShopOrder condition) {
        List<ShopOrder> list = visibleOrders(condition);
        for (ShopOrder order : list) {
            order.setItems(shopOrderItemMapper.selectByOrderId(order.getId()));
        }
        fillReviewed(list);
        return list;
    }

    public PageInfo<ShopOrder> selectPage(ShopOrder condition, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(selectAll(condition));
    }

    public ShopOrder selectById(Integer id) {
        ShopOrder order = requireOrder(id);
        if (!UserContext.isAdmin() && !order.getUserId().equals(UserContext.requireUserId())) {
            throw new CustomException(ResultCodeEnum.FORBIDDEN);
        }
        order.setItems(shopOrderItemMapper.selectByOrderId(order.getId()));
        fillReviewed(List.of(order));
        return order;
    }

    /** 发货：PAID→SHIPPED（管理端） */
    public void ship(Integer id) {
        if (shopOrderMapper.markShipped(id) == 0) {
            throw new CustomException(ResultCodeEnum.ORDER_STATUS_ERROR, "只有已支付订单可以发货");
        }
    }

    /** 确认收货：SHIPPED→COMPLETED（买家本人或管理端） */
    public void finish(Integer id) {
        ShopOrder order = requireOrder(id);
        if (!UserContext.isAdmin() && !order.getUserId().equals(UserContext.requireUserId())) {
            throw new CustomException(ResultCodeEnum.FORBIDDEN);
        }
        if (shopOrderMapper.markCompleted(id) == 0) {
            throw new CustomException(ResultCodeEnum.ORDER_STATUS_ERROR, "只有已发货订单可以确认收货");
        }
    }

    public ShopOrder cancel(Integer id) {
        return orderSagaService.cancel(id);
    }

    /** 普通用户只能看自己的订单；管理员可看全部（条件里带 userId 时按条件） */
    private List<ShopOrder> visibleOrders(ShopOrder condition) {
        if (!UserContext.isAdmin()) {
            condition.setUserId(UserContext.requireUserId());
        }
        return shopOrderMapper.selectAll(condition);
    }

    private ShopOrder requireOrder(Integer id) {
        if (ObjectUtil.isEmpty(id)) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        ShopOrder order = shopOrderMapper.selectById(id);
        if (order == null) {
            throw new CustomException(ResultCodeEnum.ORDER_NOT_EXIST_ERROR);
        }
        return order;
    }
}
