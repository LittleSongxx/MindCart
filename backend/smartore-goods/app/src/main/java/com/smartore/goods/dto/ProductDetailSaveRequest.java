package com.smartore.goods.dto;

import com.smartore.goods.entity.ProductDetail;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 商品详情保存请求（一个商品一行，服务层按 productId 判定插入或更新） */
@Getter
@Setter
public class ProductDetailSaveRequest {

    private Integer id;

    @NotNull(message = "商品ID不能为空")
    private Integer productId;

    @NotBlank(message = "商品详情不能为空")
    private String detailContent;

    private String packageInfo;

    private String afterSaleInfo;

    /** 面向 AI 检索与推荐的商品摘要 */
    private String aiSummary;

    public ProductDetail toEntity() {
        ProductDetail detail = new ProductDetail();
        detail.setId(id);
        detail.setProductId(productId);
        detail.setDetailContent(detailContent);
        detail.setPackageInfo(packageInfo);
        detail.setAfterSaleInfo(afterSaleInfo);
        detail.setAiSummary(aiSummary);
        return detail;
    }
}
