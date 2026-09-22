package com.smartore.goods.dto;

import com.smartore.goods.entity.ProductReview;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 提交评价请求。
 *
 * 不含 userId（服务层一律以网关注入的当前用户为准，防止冒名评价）、
 * 也不含 auditStatus（审核状态由服务端决定，客户端无权自审通过）。
 */
@Getter
@Setter
public class ProductReviewCreateRequest {

    @NotNull(message = "订单ID不能为空")
    private Integer orderId;

    @NotNull(message = "订单行ID不能为空")
    private Integer orderItemId;

    @NotNull(message = "商品ID不能为空")
    private Integer productId;

    @NotNull(message = "请给出评分")
    @Min(value = 1, message = "评分最低1分")
    @Max(value = 5, message = "评分最高5分")
    private Integer rating;

    @NotBlank(message = "评价内容不能为空")
    @Size(max = 500, message = "评价内容长度不能超过500")
    private String content;

    /** 评价图片地址，前端以英文逗号拼接 */
    @Size(max = 1000, message = "评价图片地址过长")
    private String images;

    public ProductReview toEntity() {
        ProductReview review = new ProductReview();
        review.setOrderId(orderId);
        review.setOrderItemId(orderItemId);
        review.setProductId(productId);
        review.setRating(rating);
        review.setContent(content);
        review.setImages(images);
        return review;
    }
}
