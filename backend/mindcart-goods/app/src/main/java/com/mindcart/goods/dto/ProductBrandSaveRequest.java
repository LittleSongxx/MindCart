package com.mindcart.goods.dto;

import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import com.mindcart.goods.entity.ProductBrand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 品牌新增/更新请求。
 * 分组口径见 {@link ProductSaveRequest}：格式边界走 {Create, Update}，必填只走 Create。
 */
@Getter
@Setter
public class ProductBrandSaveRequest {

    @NotNull(message = "品牌ID不能为空", groups = Update.class)
    private Integer id;

    @NotBlank(message = "品牌名称不能为空", groups = Create.class)
    @Size(max = 50, message = "品牌名称长度不能超过50", groups = {Create.class, Update.class})
    private String name;

    @Size(max = 255, message = "品牌LOGO地址长度不能超过255", groups = {Create.class, Update.class})
    private String logo;

    @Size(max = 255, message = "官网地址长度不能超过255", groups = {Create.class, Update.class})
    private String officialSite;

    @Min(value = 1, message = "排序值最小为1", groups = {Create.class, Update.class})
    @Max(value = 999, message = "排序值最大为999", groups = {Create.class, Update.class})
    private Integer sort;

    @Min(value = 0, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    @Max(value = 1, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    private Integer isEnabled;

    @Size(max = 500, message = "品牌简介长度不能超过500", groups = {Create.class, Update.class})
    private String description;

    public ProductBrand toEntity() {
        ProductBrand brand = new ProductBrand();
        brand.setId(id);
        brand.setName(name);
        brand.setLogo(logo);
        brand.setOfficialSite(officialSite);
        brand.setSort(sort);
        brand.setIsEnabled(isEnabled);
        brand.setDescription(description);
        return brand;
    }
}
