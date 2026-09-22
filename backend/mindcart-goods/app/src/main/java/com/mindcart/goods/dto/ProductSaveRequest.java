package com.mindcart.goods.dto;

import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import com.mindcart.goods.entity.Product;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 商品新增/更新请求。
 *
 * 只承载客户端应决定的字段：主键、改名、类目归属、价格、库存初值、上下架、推荐位、排序。
 * 不含 createTime/updateTime（时间由服务端生成）、categoryName/brandName（join 派生，服务端回填）。
 *
 * 约束分组注意：controller 用 {@code @Validated(Create.class)} / {@code @Validated(Update.class)}
 * 时**只有该分组的约束会执行**，未声明 groups 的约束属于 Default 分组、会被静默跳过。
 * 因此这里显式声明：格式与边界 → {Create, Update}（更新时也要查）；必填 → 仅 Create
 * （更新允许部分提交，缺字段由服务层原有的必填校验兜底）。
 */
@Getter
@Setter
public class ProductSaveRequest {

    @NotNull(message = "商品ID不能为空", groups = Update.class)
    private Integer id;

    /** 留空由服务端生成 SP+时间戳 */
    @Size(max = 50, message = "商品编号长度不能超过50", groups = {Create.class, Update.class})
    private String productNo;

    @NotBlank(message = "商品名称不能为空", groups = Create.class)
    @Size(max = 100, message = "商品名称长度不能超过100", groups = {Create.class, Update.class})
    private String name;

    @NotNull(message = "请选择商品分类", groups = Create.class)
    private Integer categoryId;

    @NotNull(message = "请选择商品品牌", groups = Create.class)
    private Integer brandId;

    @Size(max = 255, message = "封面地址长度不能超过255", groups = {Create.class, Update.class})
    private String coverImage;

    /** 相册地址，前端以英文逗号拼接 */
    @Size(max = 2000, message = "相册地址过长", groups = {Create.class, Update.class})
    private String albumImages;

    @NotNull(message = "商品价格不能为空", groups = Create.class)
    @DecimalMin(value = "0.01", message = "商品价格必须大于0", groups = {Create.class, Update.class})
    @Digits(integer = 10, fraction = 2, message = "商品价格最多10位整数、2位小数", groups = {Create.class, Update.class})
    private BigDecimal price;

    @DecimalMin(value = "0.01", message = "原价必须大于0", groups = {Create.class, Update.class})
    @Digits(integer = 10, fraction = 2, message = "原价最多10位整数、2位小数", groups = {Create.class, Update.class})
    private BigDecimal originalPrice;

    /**
     * 库存：管理端填写即是一次显式重设（服务层会同步库存权威表）。
     * 这里只拦负数；够不够卖由 stock 表的条件扣减保证，商品展示列不参与权威判定。
     */
    @Min(value = 0, message = "库存不能为负数", groups = {Create.class, Update.class})
    private Integer stockQuantity;

    @Size(max = 500, message = "卖点长度不能超过500", groups = {Create.class, Update.class})
    private String sellingPoint;

    @Size(max = 255, message = "标签长度不能超过255", groups = {Create.class, Update.class})
    private String tags;

    @Min(value = 0, message = "推荐位只能是0或1", groups = {Create.class, Update.class})
    @Max(value = 1, message = "推荐位只能是0或1", groups = {Create.class, Update.class})
    private Integer isRecommend;

    @Pattern(regexp = "ON_SALE|OFF_SALE", message = "商品状态只能是 ON_SALE 或 OFF_SALE",
            groups = {Create.class, Update.class})
    private String status;

    @Min(value = 1, message = "排序值最小为1", groups = {Create.class, Update.class})
    @Max(value = 999, message = "排序值最大为999", groups = {Create.class, Update.class})
    private Integer sort;

    public Product toEntity() {
        Product product = new Product();
        product.setId(id);
        product.setProductNo(productNo);
        product.setName(name);
        product.setCategoryId(categoryId);
        product.setBrandId(brandId);
        product.setCoverImage(coverImage);
        product.setAlbumImages(albumImages);
        product.setPrice(price);
        product.setOriginalPrice(originalPrice);
        product.setStockQuantity(stockQuantity);
        product.setSellingPoint(sellingPoint);
        product.setTags(tags);
        product.setIsRecommend(isRecommend);
        product.setStatus(status);
        product.setSort(sort);
        return product;
    }
}
