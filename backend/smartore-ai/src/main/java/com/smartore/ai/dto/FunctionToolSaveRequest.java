package com.smartore.ai.dto;

import com.smartore.ai.entity.FunctionTool;
import com.smartore.common.validation.Create;
import com.smartore.common.validation.Update;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 函数工具新增/更新请求（仅 ADMIN 可达）。
 *
 * serviceBean/serviceMethod 是反射调用的入口，限定为 Java 标识符安全字符集，
 * 避免这组字段被当成表达式注入。
 * toolType/invokeType 不加枚举约束：代码里只校验非空、不按值分支，写死枚举反而会拒掉
 * 现有数据（种子数据用 SERVICE）。真正按值分支的字段才用 {@code @Pattern}。
 */
@Getter
@Setter
public class FunctionToolSaveRequest {

    @NotNull(message = "工具ID不能为空", groups = Update.class)
    private Integer id;

    @NotBlank(message = "工具编码不能为空", groups = Create.class)
    @Size(max = 50, message = "工具编码长度不能超过50", groups = {Create.class, Update.class})
    private String toolCode;

    @NotBlank(message = "工具名称不能为空", groups = Create.class)
    @Size(max = 100, message = "工具名称长度不能超过100", groups = {Create.class, Update.class})
    private String toolName;

    @NotBlank(message = "工具类型不能为空", groups = Create.class)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$", message = "工具类型需为25字以内的英文编码",
            groups = {Create.class, Update.class})
    private String toolType;

    @NotBlank(message = "调用方式不能为空", groups = Create.class)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$", message = "调用方式需为25字以内的英文编码",
            groups = {Create.class, Update.class})
    private String invokeType;

    @NotBlank(message = "服务 Bean 不能为空", groups = Create.class)
    @Pattern(regexp = "^[A-Za-z_$][A-Za-z0-9_$]{0,99}$", message = "服务 Bean 名称不合法",
            groups = {Create.class, Update.class})
    private String serviceBean;

    @NotBlank(message = "服务方法不能为空", groups = Create.class)
    @Pattern(regexp = "^[A-Za-z_$][A-Za-z0-9_$]{0,99}$", message = "服务方法名不合法",
            groups = {Create.class, Update.class})
    private String serviceMethod;

    @NotBlank(message = "入参结构不能为空", groups = Create.class)
    @Size(max = 4000, message = "入参结构过长", groups = {Create.class, Update.class})
    private String inputSchema;

    @NotBlank(message = "出参结构不能为空", groups = Create.class)
    @Size(max = 4000, message = "出参结构过长", groups = {Create.class, Update.class})
    private String outputSchema;

    @Min(value = 0, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    @Max(value = 1, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    private Integer isEnabled;

    @Min(value = 1, message = "排序值最小为1", groups = {Create.class, Update.class})
    @Max(value = 999, message = "排序值最大为999", groups = {Create.class, Update.class})
    private Integer sort;

    @Size(max = 255, message = "备注长度不能超过255", groups = {Create.class, Update.class})
    private String remark;

    public FunctionTool toEntity() {
        FunctionTool tool = new FunctionTool();
        tool.setId(id);
        tool.setToolCode(toolCode);
        tool.setToolName(toolName);
        tool.setToolType(toolType);
        tool.setInvokeType(invokeType);
        tool.setServiceBean(serviceBean);
        tool.setServiceMethod(serviceMethod);
        tool.setInputSchema(inputSchema);
        tool.setOutputSchema(outputSchema);
        tool.setIsEnabled(isEnabled);
        tool.setSort(sort);
        tool.setRemark(remark);
        return tool;
    }
}
