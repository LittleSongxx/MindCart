package com.mindcart.ai.mq;

import com.mindcart.ai.config.AiRabbitTopology;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 死信观察者：三个业务队列的 DLX 兜底都路由到 *.dead 队列，
 * 此前没有任何消费者——事件进了死信只能上 Rabbit 管理台人工发现。
 * 这里统一消费并打 ERROR 日志（告警规则盯日志/日志采集即可感知），
 * 只 ack 不重投：死信意味着消费端确认失败（如镜像库长时间不可用），
 * 无脑重投回死信队列只会循环；人工修复后按 event_id 从 trade 账本重放。
 */
@Component
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    @RabbitListener(queues = AiRabbitTopology.QUEUE_TRADE_EVENT_DEAD)
    public void onTradeEventDead(String payload, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.error("交易事件进入死信（AI 镜像将出现空洞，需人工从 trade 账本重放）：{}", brief(payload));
        channel.basicAck(deliveryTag, false);
    }

    @RabbitListener(queues = AiRabbitTopology.QUEUE_GUIDE_DEAD)
    public void onGuideTaskDead(String payload, Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.error("导购任务消息进入死信：{}", brief(payload));
        channel.basicAck(deliveryTag, false);
    }

    @RabbitListener(queues = AiRabbitTopology.QUEUE_EMBEDDING_DEAD)
    public void onEmbeddingJobDead(String payload, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.error("向量批任务消息进入死信：{}", brief(payload));
        channel.basicAck(deliveryTag, false);
    }

    private String brief(String payload) {
        return payload != null && payload.length() > 500 ? payload.substring(0, 500) + "…" : String.valueOf(payload);
    }
}
