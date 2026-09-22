package com.mindcart.ai.dto;

import com.mindcart.ai.entity.PromptTemplate;
import com.mindcart.common.validation.Create;
import com.mindcart.common.validation.Update;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 提示词模板新增/更新请求（仅 ADMIN 可达）。
 * businessType/outputFormat 不加枚举：代码只做非空校验，写死会拒掉现有数据
 * （种子数据里 businessType 有 GUIDE/QA/REVIEW/OPERATION 等多种）。
 */
@Getter
@Setter
public class PromptTemplateSaveRequest {

    @NotNull(message = "模板ID不能为空", groups = Update.class)
    private Integer id;

    @NotBlank(message = "模板编码不能为空", groups = Create.class)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$", message = "模板编码需为50字以内的英文大写编码",
            groups = {Create.class, Update.class})
    private String templateCode;

    @NotBlank(message = "模板名称不能为空", groups = Create.class)
    @Size(max = 100, message = "模板名称长度不能超过100", groups = {Create.class, Update.class})
    private String templateName;

    @NotBlank(message = "业务类型不能为空", groups = Create.class)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$", message = "业务类型需为50字以内的英文编码",
            groups = {Create.class, Update.class})
    private String businessType;

    @NotBlank(message = "系统提示词不能为空", groups = Create.class)
    @Size(max = 8000, message = "系统提示词过长", groups = {Create.class, Update.class})
    private String systemPrompt;

    @NotBlank(message = "用户提示词不能为空", groups = Create.class)
    @Size(max = 8000, message = "用户提示词过长", groups = {Create.class, Update.class})
    private String userPrompt;

    @Size(max = 1000, message = "输出格式说明过长", groups = {Create.class, Update.class})
    private String outputFormat;

    @Min(value = 0, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    @Max(value = 1, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    private Integer isEnabled;

    @Size(max = 255, message = "备注长度不能超过255", groups = {Create.class, Update.class})
    private String remark;

    public PromptTemplate toEntity() {
        PromptTemplate template = new PromptTemplate();
        template.setId(id);
        template.setTemplateCode(templateCode);
        template.setTemplateName(templateName);
        template.setBusinessType(businessType);
        template.setSystemPrompt(systemPrompt);
        template.setUserPrompt(userPrompt);
        template.setOutputFormat(outputFormat);
        template.setIsEnabled(isEnabled);
        template.setRemark(remark);
        return template;
    }
}
