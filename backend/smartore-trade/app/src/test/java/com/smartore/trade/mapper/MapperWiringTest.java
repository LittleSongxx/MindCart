package com.smartore.trade.mapper;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper 绑定完整性守卫。
 *
 * MyBatis 的绑定错误只在"方法真被调用的那一刻"才抛 BindingException（启动期完全静默）。
 * 本项目就踩过一次：ShopOrderMapper 里声明过既无注解也无 XML 的 deleteById，
 * 直到有代码去调它才会在运行期炸。这里把"声明的方法都有绑定"变成构建期的确定性检查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "smartore.internal-token=test-internal-token",
                "smartore.gateway-token=test-gateway-token",
                "smartore.cache.enabled=false"
        })
@Testcontainers
class MapperWiringTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    /** 中继任务会去投递 Outbox，这里换掉 broker 免得测试连 Rabbit */
    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void everyDeclaredMapperMethodHasABoundStatement() {
        Configuration configuration = sqlSessionFactory.getConfiguration();
        List<String> unbound = new ArrayList<>();

        for (Class<?> mapper : List.of(
                ShopOrderMapper.class,
                ShopOrderItemMapper.class,
                ShoppingCartMapper.class,
                TradeEventLedgerMapper.class,
                OrderRequestIdempotencyMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || method.isDefault()) {
                    continue;
                }
                String statementId = mapper.getName() + "." + method.getName();
                if (!configuration.hasStatement(statementId, false)) {
                    unbound.add(statementId);
                }
            }
        }

        assertTrue(unbound.isEmpty(),
                "以下 Mapper 方法没有绑定任何 SQL（缺注解或 XML），调用即抛 BindingException：" + unbound);
    }
}
