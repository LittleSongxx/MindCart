package com.mindcart.ai.mq;

import com.mindcart.ai.config.AiRabbitTopology;
import com.mindcart.ai.service.ProductKnowledgeEmbeddingService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * 向量批量生成消费者：Redis SET NX 分布式锁保证全集群同一时刻只有一个批量任务
 * （单体版是单机 AtomicBoolean，多实例下会互相覆盖写入）。
 */
@Component
public class EmbeddingJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingJobConsumer.class);

    private final ProductKnowledgeEmbeddingService embeddingService;
    private final StringRedisTemplate redisTemplate;

    public EmbeddingJobConsumer(ProductKnowledgeEmbeddingService embeddingService,
                                StringRedisTemplate redisTemplate) {
        this.embeddingService = embeddingService;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = AiRabbitTopology.QUEUE_EMBEDDING_JOB)
    public void onMessage(String message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        Boolean locked = false;
        try {
            locked = redisTemplate.opsForValue().setIfAbsent(
                    ProductKnowledgeEmbeddingService.LOCK_KEY, "1", Duration.ofHours(2));
            if (Boolean.FALSE.equals(locked)) {
                // 已有批量任务在跑：确认消息，不重复执行
                channel.basicAck(deliveryTag, false);
                return;
            }
            embeddingService.runGenerateAll();
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("向量批量生成异常：{}", e.getMessage(), e);
            channel.basicAck(deliveryTag, false); // 进度与失败切片留痕，人工按需重跑
        } finally {
            if (Boolean.TRUE.equals(locked)) {
                redisTemplate.delete(ProductKnowledgeEmbeddingService.LOCK_KEY);
            }
        }
    }
}
