package com.mindcart.goods.dto;

import com.mindcart.goods.entity.ProductReview;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 评价审核请求。调用方必须是 ADMIN（网关 RBAC + 服务层二次校验），
 * DTO 只开放「审核哪条 + 改成什么状态」两个字段。
 */
@Getter
@Setter
public class ProductReviewAuditRequest {

    @NotNull(message = "评价ID不能为空")
    private Integer id;

    @NotNull(message = "审核状态不能为空")
    @Pattern(regexp = "APPROVED|REJECTED|PENDING", message = "审核状态只能是 APPROVED / REJECTED / PENDING")
    private String auditStatus;

    public ProductReview toEntity() {
        ProductReview review = new ProductReview();
        review.setId(id);
        review.setAuditStatus(auditStatus);
        return review;
    }
}
