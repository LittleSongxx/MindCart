package com.mindcart.goods.dto;

import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import com.mindcart.goods.entity.AfterSaleRule;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 售后规则新增/更新请求。
 * 分组口径见 {@link ProductSaveRequest}：格式边界走 {Create, Update}，必填只走 Create。
 */
@Getter
@Setter
public class AfterSaleRuleSaveRequest {

    @NotNull(message = "规则ID不能为空", groups = Update.class)
    private Integer id;

    @NotBlank(message = "规则名称不能为空", groups = Create.class)
    @Size(max = 100, message = "规则名称长度不能超过100", groups = {Create.class, Update.class})
    private String ruleName;

    @NotBlank(message = "规则类型不能为空", groups = Create.class)
    @Size(max = 50, message = "规则类型长度不能超过50", groups = {Create.class, Update.class})
    private String ruleType;

    /** 为空表示全类目通用 */
    private Integer categoryId;

    @NotBlank(message = "适用场景不能为空", groups = Create.class)
    @Size(max = 100, message = "适用场景长度不能超过100", groups = {Create.class, Update.class})
    private String applyScene;

    @NotBlank(message = "条件说明不能为空", groups = Create.class)
    @Size(max = 1000, message = "条件说明长度不能超过1000", groups = {Create.class, Update.class})
    private String conditionText;

    @NotBlank(message = "处理流程不能为空", groups = Create.class)
    @Size(max = 1000, message = "处理流程长度不能超过1000", groups = {Create.class, Update.class})
    private String processText;

    @Size(max = 50, message = "时限说明长度不能超过50", groups = {Create.class, Update.class})
    private String timeLimit;

    @Size(max = 100, message = "联系渠道长度不能超过100", groups = {Create.class, Update.class})
    private String contactChannel;

    @Min(value = 0, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    @Max(value = 1, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    private Integer isEnabled;

    @Min(value = 1, message = "排序值最小为1", groups = {Create.class, Update.class})
    @Max(value = 999, message = "排序值最大为999", groups = {Create.class, Update.class})
    private Integer sort;

    public AfterSaleRule toEntity() {
        AfterSaleRule rule = new AfterSaleRule();
        rule.setId(id);
        rule.setRuleName(ruleName);
        rule.setRuleType(ruleType);
        rule.setCategoryId(categoryId);
        rule.setApplyScene(applyScene);
        rule.setConditionText(conditionText);
        rule.setProcessText(processText);
        rule.setTimeLimit(timeLimit);
        rule.setContactChannel(contactChannel);
        rule.setIsEnabled(isEnabled);
        rule.setSort(sort);
        return rule;
    }
}
