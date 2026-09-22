package com.mindcart.user.dto;

import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import com.mindcart.user.entity.UserAddress;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 收货地址新增/更新请求（Person.vue 的新增与编辑复用同一个表单）。
 *
 * 归属不收 userId：服务层一律以当前登录用户为准，并且更新前会校验地址归属（requireOwned）。
 * 分组口径：格式边界走 {Create, Update}，必填只走 Create（编辑允许只提交改动字段，
 * 服务层的既有必填校验仍然兜底）。
 */
@Getter
@Setter
public class AddressSaveRequest {

    @NotNull(message = "地址ID不能为空", groups = Update.class)
    private Integer id;

    @NotBlank(message = "收货人不能为空", groups = Create.class)
    @Size(max = 50, message = "收货人姓名长度不能超过50", groups = {Create.class, Update.class})
    private String receiverName;

    /** 手机号：收货人联系方式，用中国大陆手机号严校验（种子数据与前端提交都符合该格式） */
    @NotBlank(message = "收货人手机号不能为空", groups = Create.class)
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的手机号",
            groups = {Create.class, Update.class})
    private String receiverPhone;

    @Size(max = 50, message = "省份长度不能超过50", groups = {Create.class, Update.class})
    private String province;

    @Size(max = 50, message = "城市长度不能超过50", groups = {Create.class, Update.class})
    private String city;

    @Size(max = 50, message = "区县长度不能超过50", groups = {Create.class, Update.class})
    private String district;

    @NotBlank(message = "详细地址不能为空", groups = Create.class)
    @Size(max = 255, message = "详细地址长度不能超过255", groups = {Create.class, Update.class})
    private String detailAddress;

    @Size(max = 20, message = "邮政编码长度不能超过20", groups = {Create.class, Update.class})
    private String postalCode;

    @Min(value = 0, message = "默认地址标记只能是0或1", groups = {Create.class, Update.class})
    @Max(value = 1, message = "默认地址标记只能是0或1", groups = {Create.class, Update.class})
    private Integer isDefault;

    public UserAddress toEntity() {
        UserAddress address = new UserAddress();
        address.setId(id);
        address.setReceiverName(receiverName);
        address.setReceiverPhone(receiverPhone);
        address.setProvince(province);
        address.setCity(city);
        address.setDistrict(district);
        address.setDetailAddress(detailAddress);
        address.setPostalCode(postalCode);
        address.setIsDefault(isDefault);
        return address;
    }
}
