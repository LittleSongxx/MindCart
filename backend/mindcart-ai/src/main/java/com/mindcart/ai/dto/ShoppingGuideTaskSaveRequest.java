package com.mindcart.ai.dto;

import com.mindcart.ai.entity.ShoppingGuideTask;
import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 导购任务新增/更新请求。
 *
 * 刻意不含 userId：服务层一律以当前登录用户为任务归属（需求文本与预算属个人信息，
 * 且任务会被回填真实姓名），把 userId 从请求体里去掉后越权字段就彻底不存在了。
 * 也不含 status/结果字段——那些是执行侧写回的，客户端无权指定。
 * taskNo 保留：管理端表单允许手填任务编号（留空则服务端生成）。
 */
@Getter
@Setter
public class ShoppingGuideTaskSaveRequest {

    @NotNull(message = "任务ID不能为空", groups = Update.class)
    private Integer id;

    @Size(max = 80, message = "任务编号长度不能超过80", groups = {Create.class, Update.class})
    private String taskNo;

    @NotBlank(message = "购物需求不能为空", groups = Create.class)
    @Size(max = 2000, message = "购物需求过长", groups = {Create.class, Update.class})
    private String demandText;

    @DecimalMin(value = "0.0", message = "预算不能为负数", groups = {Create.class, Update.class})
    @Digits(integer = 10, fraction = 2, message = "预算最多10位整数、2位小数", groups = {Create.class, Update.class})
    private BigDecimal budgetAmount;

    /** 可选：用于相似商品召回锚点 */
    private Integer productId;

    public ShoppingGuideTask toEntity() {
        ShoppingGuideTask task = new ShoppingGuideTask();
        task.setId(id);
        task.setTaskNo(taskNo);
        task.setDemandText(demandText);
        task.setBudgetAmount(budgetAmount);
        task.setProductId(productId);
        return task;
    }
}
