package com.mindcart.ai.mq;

import com.mindcart.ai.agent.AgentExecutor;
import com.mindcart.ai.config.AiRabbitTopology;
import com.mindcart.ai.entity.ShoppingGuideTask;
import com.mindcart.ai.mapper.ShoppingGuideTaskMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 导购任务消费者：手动 ack。
 * - 任务非 WAITING（已执行/执行中）→ 直接 ack 丢弃（幂等）；
 * - 执行异常 → ack 并标记 FAILED（失败原因落库，页面可见），不无限重投毒丸消息；
 * - 兜底扫描：WAITING 超过 90 秒的任务（入队丢失/消费者宕机）自动重发。
 */
@Component
public class GuideTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(GuideTaskConsumer.class);

    private final AgentExecutor agentExecutor;
    private final ShoppingGuideTaskMapper taskMapper;
    private final GuideTaskPublisher publisher;

    public GuideTaskConsumer(AgentExecutor agentExecutor, ShoppingGuideTaskMapper taskMapper,
                             GuideTaskPublisher publisher) {
        this.agentExecutor = agentExecutor;
        this.taskMapper = taskMapper;
        this.publisher = publisher;
    }

    @RabbitListener(queues = AiRabbitTopology.QUEUE_GUIDE_TASK)
    public void onMessage(String taskIdText, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        Integer taskId = null;
        try {
            taskId = Integer.valueOf(taskIdText.trim());
        } catch (NumberFormatException e) {
            // 毒丸消息：直接 ack 丢弃（requeue 只会无限重放；任务表才是真源，消息只是触发器）
            log.error("导购任务消息格式非法，已丢弃（不会进死信队列）：{}", taskIdText);
            channel.basicAck(deliveryTag, false);
            return;
        }
        try {
            ShoppingGuideTask task = taskMapper.selectById(taskId);
            if (task == null || !"WAITING".equals(task.getStatus())) {
                channel.basicAck(deliveryTag, false); // 幂等：非待执行任务直接确认
                return;
            }
            task.setStatus("RUNNING");
            task.setUpdateTime(cn.hutool.core.date.DateUtil.now());
            taskMapper.updateById(task);

            agentExecutor.execute(task);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("导购任务执行异常（taskId={}）：{}", taskId, e.getMessage(), e);
            markFailed(taskId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            // 失败原因已落库，消息确认；无脑 requeue 只会无限重放
            channel.basicAck(deliveryTag, false);
        }
    }

    private void markFailed(Integer taskId, String message) {
        try {
            ShoppingGuideTask task = taskMapper.selectById(taskId);
            if (task != null && !"DONE".equals(task.getStatus())) {
                task.setStatus("FAILED");
                task.setExecuteMessage(cn.hutool.core.util.StrUtil.maxLength(message, 500));
                task.setUpdateTime(cn.hutool.core.date.DateUtil.now());
                taskMapper.updateById(task);
            }
        } catch (Exception e) {
            log.error("标记任务失败状态出错（taskId={}）：{}", taskId, e.getMessage());
        }
    }

    /**
     * 兜底：WAITING 超 90 秒重新入队；RUNNING 超时重置为 WAITING 重发
     * （消费者宕机/执行线程挂死时任务不至于永远停留中间态——重放安全：新一轮执行会新建 AgentRun）。
     * RUNNING 阈值必须覆盖 Agent 最坏耗时：10 轮 × LLM 60s × 3 次重试 ≈ 30 分钟，
     * 取 35 分钟；阈值小于最坏耗时时，兜底重发会与仍在执行的消费者并发双跑。
     */
    @Scheduled(fixedDelay = 30000)
    public void rescueStaleWaitingTasks() {
        for (ShoppingGuideTask task : taskMapper.selectStaleWaiting(90)) {
            log.warn("导购任务兜底重发（taskId={}，taskNo={}）", task.getId(), task.getTaskNo());
            publisher.publish(task.getId());
        }
        for (ShoppingGuideTask task : taskMapper.selectStaleByStatus("RUNNING", 2100)) {
            log.warn("导购任务 RUNNING 超时，重置重发（taskId={}）", task.getId());
            task.setStatus("WAITING");
            task.setUpdateTime(cn.hutool.core.date.DateUtil.now());
            taskMapper.updateById(task);
            publisher.publish(task.getId());
        }
    }
}
