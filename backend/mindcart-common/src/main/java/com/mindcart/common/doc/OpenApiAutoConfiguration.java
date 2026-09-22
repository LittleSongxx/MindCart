package com.mindcart.common.doc;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 接口文档的公共 OpenAPI 元信息：不写这一层，每个服务的文档标题都会是默认的
 * "OpenAPI definition"，聚合页上下拉切服务时分不清谁是谁。
 *
 * 放在 common 并用自动配置发布：服务侧只需要依赖 springdoc，无需各写一份 config 类。
 *
 * 已知口径限制：springdoc 读取 Bean Validation 注解时**不区分校验分组**，
 * 因此像 {@code ProductSaveRequest.id} 这种「仅更新时必填」的字段，
 * 在文档里会被标成 add 与 update 都必填。这是 springdoc 对分组的固有限制，
 * 真实约束以 DTO 注释与全局 4002 校验结果为准。
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
public class OpenApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI mindcartOpenApi(
            @Value("${spring.application.name:mindcart}") String applicationName,
            @Value("${server.port:}") String port) {
        Info info = new Info()
                .title(applicationName + " 接口文档")
                .description("MindCart 智能商城 · " + applicationName + "。"
                        + "内部接口（/internal/**）不经网关暴露，因此不在本文档的对外语义内。"
                        + "需要登录的接口请在请求头带上 token（登录接口见 mindcart-user）。");
        if (!port.isBlank()) {
            info.version("0.1.0 · port " + port);
        } else {
            info.version("0.1.0");
        }
        return new OpenAPI().info(info);
    }
}
