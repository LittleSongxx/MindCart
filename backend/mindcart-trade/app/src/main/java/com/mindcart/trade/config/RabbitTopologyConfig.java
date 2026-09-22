package com.mindcart.trade.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 交易事件拓扑。队列全部 durable + DLX，消费失败进死信队列（有告警规则盯着），
 * 消息不会静默丢失 —— 与 Outbox 中继共同构成"至少一次投递 + 消费端幂等"。
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String EXCHANGE = "mindcart.trade";
    public static final String QUEUE_EVENT = "mindcart.trade.event";
    public static final String DLX = "mindcart.dlx";
    public static final String QUEUE_EVENT_DEAD = "mindcart.trade.event.dead";

    @Bean
    public DirectExchange tradeExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DLX).durable(true).build();
    }

    @Bean
    public Queue tradeEventQueue() {
        return QueueBuilder.durable(QUEUE_EVENT)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(QUEUE_EVENT_DEAD)
                .quorum() // quorum 队列：集群多数派确认，节点故障不丢消息
                .build();
    }

    @Bean
    public Queue tradeEventDeadQueue() {
        return QueueBuilder.durable(QUEUE_EVENT_DEAD).quorum().build();
    }

    @Bean
    public Binding tradeEventBinding() {
        return BindingBuilder.bind(tradeEventQueue()).to(tradeExchange()).with("trade.event");
    }

    @Bean
    public Binding tradeEventDeadBinding() {
        return BindingBuilder.bind(tradeEventDeadQueue()).to(deadLetterExchange()).with(QUEUE_EVENT_DEAD);
    }
}
