package com.smartore.ai.mq;

import com.smartore.ai.config.AiRabbitTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** 导购任务消息发布：taskId 入队。任务表是事实源，消息丢了由兜底扫描重发 */
@Component
public class GuideTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(GuideTaskPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public GuideTaskPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(Integer taskId) {
        try {
            rabbitTemplate.convertAndSend(AiRabbitTopology.EXCHANGE, AiRabbitTopology.ROUTING_GUIDE_TASK,
                    String.valueOf(taskId));
        } catch (Exception e) {
            // 入队失败不阻塞业务：WAITING 任务由 GuideTaskRescue 兜底重发
            log.error("导购任务入队失败（taskId={}），等待兜底扫描重发：{}", taskId, e.getMessage());
        }
    }
}
