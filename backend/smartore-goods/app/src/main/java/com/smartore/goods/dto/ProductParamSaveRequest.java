package com.smartore.goods.dto;

import com.smartore.common.validation.Create;
import com.smartore.common.validation.Update;
import com.smartore.goods.entity.ProductParam;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品参数新增/更新请求。
 * 分组口径见 {@link ProductSaveRequest}：格式边界走 {Create, Update}，必填只走 Create。
 */
@Getter
@Setter
public class ProductParamSaveRequest {

    @NotNull(message = "参数ID不能为空", groups = Update.class)
    private Integer id;

    @NotNull(message = "商品ID不能为空", groups = Create.class)
    private Integer productId;

    @NotBlank(message = "参数分组不能为空", groups = Create.class)
    @Size(max = 50, message = "参数分组长度不能超过50", groups = {Create.class, Update.class})
    private String paramGroup;

    @NotBlank(message = "参数名不能为空", groups = Create.class)
    @Size(max = 100, message = "参数名长度不能超过100", groups = {Create.class, Update.class})
    private String paramName;

    @NotBlank(message = "参数值不能为空", groups = Create.class)
    @Size(max = 500, message = "参数值长度不能超过500", groups = {Create.class, Update.class})
    private String paramValue;

    @Min(value = 1, message = "排序值最小为1", groups = {Create.class, Update.class})
    @Max(value = 999, message = "排序值最大为999", groups = {Create.class, Update.class})
    private Integer sort;

    @Min(value = 0, message = "是否核心参数只能是0或1", groups = {Create.class, Update.class})
    @Max(value = 1, message = "是否核心参数只能是0或1", groups = {Create.class, Update.class})
    private Integer isCore;

    public ProductParam toEntity() {
        ProductParam param = new ProductParam();
        param.setId(id);
        param.setProductId(productId);
        param.setParamGroup(paramGroup);
        param.setParamName(paramName);
        param.setParamValue(paramValue);
        param.setSort(sort);
        param.setIsCore(isCore);
        return param;
    }
}
