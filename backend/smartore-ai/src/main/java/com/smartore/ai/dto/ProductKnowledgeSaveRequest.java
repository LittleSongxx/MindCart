package com.smartore.ai.dto;

import com.smartore.ai.entity.ProductKnowledge;
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
 * 商品知识条目新增/更新请求（仅 ADMIN 可达）。
 *
 * 长度上限对齐库表列宽：title varchar(200)、knowledge_type/source_type varchar(50)。
 * knowledge_type/source_type 不加枚举：代码不按值分支，且导入来源会写入 MANUAL/AFTER_SALE_RULE 等多种值。
 */
@Getter
@Setter
public class ProductKnowledgeSaveRequest {

    @NotNull(message = "知识条目ID不能为空", groups = Update.class)
    private Integer id;

    @NotNull(message = "商品ID不能为空", groups = Create.class)
    private Integer productId;

    @NotBlank(message = "知识类型不能为空", groups = Create.class)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$", message = "知识类型需为50字以内的英文编码",
            groups = {Create.class, Update.class})
    private String knowledgeType;

    @NotBlank(message = "标题不能为空", groups = Create.class)
    @Size(max = 200, message = "标题长度不能超过200", groups = {Create.class, Update.class})
    private String title;

    @NotBlank(message = "知识内容不能为空", groups = Create.class)
    @Size(max = 20000, message = "知识内容过长", groups = {Create.class, Update.class})
    private String content;

    @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$", message = "来源类型需为50字以内的英文编码",
            groups = {Create.class, Update.class})
    private String sourceType;

    @Min(value = 0, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    @Max(value = 1, message = "启用状态只能是0或1", groups = {Create.class, Update.class})
    private Integer isEnabled;

    @Min(value = 1, message = "排序值最小为1", groups = {Create.class, Update.class})
    @Max(value = 999, message = "排序值最大为999", groups = {Create.class, Update.class})
    private Integer sort;

    public ProductKnowledge toEntity() {
        ProductKnowledge knowledge = new ProductKnowledge();
        knowledge.setId(id);
        knowledge.setProductId(productId);
        knowledge.setKnowledgeType(knowledgeType);
        knowledge.setTitle(title);
        knowledge.setContent(content);
        knowledge.setSourceType(sourceType);
        knowledge.setIsEnabled(isEnabled);
        knowledge.setSort(sort);
        return knowledge;
    }
}
