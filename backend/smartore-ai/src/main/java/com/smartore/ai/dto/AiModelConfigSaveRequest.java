package com.smartore.ai.dto;

import com.smartore.ai.entity.AiModelConfig;
import com.smartore.common.validation.Create;
import com.smartore.common.validation.Update;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
 * 模型配置新增/更新请求（仅 ADMIN 可达）。
 *
 * apiKey 在库里是 AES 加密存储、出参只回掩码；这里只限长度与格式（非空、无空白换行），
 * 不做字符集强校验——key 形态随供应商变化。
 * 分组口径：格式边界走 {Create, Update}，必填只走 Create。
 */
@Getter
@Setter
public class AiModelConfigSaveRequest {

    @NotNull(message = "配置ID不能为空", groups = Update.class)
    private Integer id;

    @NotBlank(message = "模型供应商不能为空", groups = Create.class)
    @Size(max = 50, message = "供应商长度不能超过50", groups = {Create.class, Update.class})
    private String provider;

    @NotBlank(message = "模型名称不能为空", groups = Create.class)
    @Size(max = 100, message = "模型名称长度不能超过100", groups = {Create.class, Update.class})
    private String modelName;

    @NotBlank(message = "模型类型不能为空", groups = Create.class)
    @Pattern(regexp = "CHAT|EMBEDDING", message = "模型类型只能是 CHAT 或 EMBEDDING",
            groups = {Create.class, Update.class})
    private String modelType;

    @NotBlank(message = "接口地址不能为空", groups = Create.class)
    @Size(max = 255, message = "接口地址长度不能超过255", groups = {Create.class, Update.class})
    private String baseUrl;

    @NotBlank(message = "API Key 不能为空", groups = Create.class)
    @Size(max = 512, message = "API Key 长度不能超过512", groups = {Create.class, Update.class})
    private String apiKey;

    /** 采样温度：负值无意义，2 以上属于无约束采样 */
    @DecimalMin(value = "0.0", message = "温度不能小于0", groups = {Create.class, Update.class})
    @DecimalMax(value = "2.0", message = "温度不能大于2", groups = {Create.class, Update.class})
    private BigDecimal temperature;

    @Min(value = 1, message = "最大输出长度最小为1", groups = {Create.class, Update.class})
    @Max(value = 200000, message = "最大输出长度超出合理范围", groups = {Create.class, Update.class})
    private Integer maxTokens;

    @Min(value = 0, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    @Max(value = 1, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    private Integer isEnabled;

    @Size(max = 255, message = "备注长度不能超过255", groups = {Create.class, Update.class})
    private String remark;

    public AiModelConfig toEntity() {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setProvider(provider);
        config.setModelName(modelName);
        config.setModelType(modelType);
        config.setBaseUrl(baseUrl);
        config.setApiKey(apiKey);
        config.setTemperature(temperature);
        config.setMaxTokens(maxTokens);
        config.setIsEnabled(isEnabled);
        config.setRemark(remark);
        return config;
    }
}
