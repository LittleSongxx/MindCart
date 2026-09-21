package com.smartore.voice.integration;

import com.smartore.common.result.Result;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import com.smartore.trade.api.OrderBriefVO;
import com.smartore.trade.api.OrderFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 语音查订单（只读）：把"我的订单/发货了吗/最近买了什么"接到 trade 的真实数据上。
 * <p>
 * 此前语音只能下单，不能查（问订单类被归到 OUT_OF_SCOPE 走兜底），是能力缺口。
 * 走规则前置分支（关键词命中即进），不占用意图分类的 LLM 往返：查订单是确定性需求，
 * 规则更快也更稳；订单事实全部来自 trade，商品名经 goods 批量补全。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderFeignClient orderClient;
    private final GoodsFeignClient goodsClient;

    /** 是否为"查我的订单"类表达（词表刻意收窄，避免抢走"发什么物流"这类政策问题） */
    public static boolean looksLikeOrderQuery(String utterance) {
        if (utterance == null) return false;
        for (String kw : List.of("我的订单", "订单状态", "订单号", "订单到哪", "什么时候发货",
                "发货了吗", "发货了没", "到哪了", "物流信息", "买的东西", "买的那个", "买的书", "刚买的")) {
            if (utterance.contains(kw)) return true;
        }
        return false;
    }

    /** 订单查询口播；查询失败返回 null（调用方走兜底话术） */
    public String answer(Long userId) {
        try {
            Result<List<OrderBriefVO>> r = orderClient.recentOfUser(userId.intValue(), 3);
            List<OrderBriefVO> orders = (r != null && "200".equals(r.getCode())) ? r.getData() : null;
            if (orders == null || orders.isEmpty()) {
                return "你这边还没有订单记录，要不要我帮你挑点什么？";
            }
            OrderBriefVO latest = orders.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("你最近有 %d 个订单，最近一单尾号 %s，%s 元，%s。",
                    orders.size(), tail(latest.getOrderNo()), amount(latest.getTotalAmount()),
                    statusCn(latest.getStatus())));

            Result<List<Integer>> idsRes = orderClient.recentProductIds(userId.intValue(), 3);
            List<Integer> ids = (idsRes != null && "200".equals(idsRes.getCode())) ? idsRes.getData() : null;
            if (ids != null && !ids.isEmpty()) {
                Result<List<ProductVO>> pr = goodsClient.getProducts(ids);
                List<ProductVO> products = (pr != null && "200".equals(pr.getCode())) ? pr.getData() : null;
                if (products != null && !products.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (ProductVO p : products) {
                        if (p != null && p.getName() != null) names.add(p.getName());
                    }
                    if (!names.isEmpty()) {
                        sb.append("买过：").append(String.join("、", names)).append("。");
                    }
                }
            }
            sb.append("还要看别的吗？");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[OrderQuery] 查询失败（走兜底）: {}", e.getMessage());
            return null;
        }
    }

    private static String tail(String orderNo) {
        if (orderNo == null) return "未知";
        return orderNo.length() >= 6 ? orderNo.substring(orderNo.length() - 6) : orderNo;
    }

    private static String amount(BigDecimal a) {
        return a == null ? "金额未知" : a.stripTrailingZeros().toPlainString();
    }

    private static String statusCn(String status) {
        if (status == null) return "状态未知";
        return switch (status) {
            case "PAYING" -> "待支付";
            case "PAID" -> "已付款";
            case "SHIPPED" -> "已发货";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            case "PAY_FAILED" -> "支付失败";
            default -> status;
        };
    }
}
