package com.mindcart.ai.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 域消息拓扑（导购任务 / 向量批量任务 / 交易事件镜像），全部 quorum + DLX。
 * 交易事件队列声明与 trade 服务一致（双方幂等声明，谁先起都能建好拓扑）。
 */
@Configuration
public class AiRabbitTopology {

    public static final String EXCHANGE = "mindcart.ai";
    public static final String EXCHANGE_TRADE = "mindcart.trade";
    public static final String ROUTING_GUIDE_TASK = "guide.task";
    public static final String ROUTING_EMBEDDING_JOB = "embedding.job";
    public static final String ROUTING_TRADE_EVENT = "trade.event";

    public static final String QUEUE_GUIDE_TASK = "mindcart.guide.task";
    public static final String QUEUE_EMBEDDING_JOB = "mindcart.embedding.job";
    public static final String QUEUE_TRADE_EVENT = "mindcart.trade.event";
    public static final String DLX = "mindcart.dlx";
    public static final String QUEUE_GUIDE_DEAD = "mindcart.guide.task.dead";
    public static final String QUEUE_EMBEDDING_DEAD = "mindcart.embedding.job.dead";
    public static final String QUEUE_TRADE_EVENT_DEAD = "mindcart.trade.event.dead";

    @Bean
    public DirectExchange aiExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange tradeExchangeForMirror() {
        return ExchangeBuilder.directExchange(EXCHANGE_TRADE).durable(true).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DLX).durable(true).build();
    }

    @Bean
    public Queue guideTaskQueue() {
        return QueueBuilder.durable(QUEUE_GUIDE_TASK)
                .deadLetterExchange(DLX).deadLetterRoutingKey(QUEUE_GUIDE_DEAD)
                .quorum().build();
    }

    @Bean
    public Queue embeddingJobQueue() {
        return QueueBuilder.durable(QUEUE_EMBEDDING_JOB)
                .deadLetterExchange(DLX).deadLetterRoutingKey(QUEUE_EMBEDDING_DEAD)
                .quorum().build();
    }

    @Bean
    public Queue tradeEventQueue() {
        return QueueBuilder.durable(QUEUE_TRADE_EVENT)
                .deadLetterExchange(DLX).deadLetterRoutingKey(QUEUE_TRADE_EVENT_DEAD)
                .quorum().build();
    }

    @Bean
    public Queue guideDeadQueue() {
        return QueueBuilder.durable(QUEUE_GUIDE_DEAD).quorum().build();
    }

    @Bean
    public Queue embeddingDeadQueue() {
        return QueueBuilder.durable(QUEUE_EMBEDDING_DEAD).quorum().build();
    }

    @Bean
    public Queue tradeEventDeadQueue() {
        return QueueBuilder.durable(QUEUE_TRADE_EVENT_DEAD).quorum().build();
    }

    @Bean
    public Binding guideTaskBinding() {
        return BindingBuilder.bind(guideTaskQueue()).to(aiExchange()).with(ROUTING_GUIDE_TASK);
    }

    @Bean
    public Binding embeddingJobBinding() {
        return BindingBuilder.bind(embeddingJobQueue()).to(aiExchange()).with(ROUTING_EMBEDDING_JOB);
    }

    @Bean
    public Binding tradeEventBinding() {
        return BindingBuilder.bind(tradeEventQueue()).to(tradeExchangeForMirror()).with(ROUTING_TRADE_EVENT);
    }

    @Bean
    public Binding guideDeadBinding() {
        return BindingBuilder.bind(guideDeadQueue()).to(deadLetterExchange()).with(QUEUE_GUIDE_DEAD);
    }

    @Bean
    public Binding embeddingDeadBinding() {
        return BindingBuilder.bind(embeddingDeadQueue()).to(deadLetterExchange()).with(QUEUE_EMBEDDING_DEAD);
    }

    @Bean
    public Binding tradeEventDeadBinding() {
        return BindingBuilder.bind(tradeEventDeadQueue()).to(deadLetterExchange()).with(QUEUE_TRADE_EVENT_DEAD);
    }
}
