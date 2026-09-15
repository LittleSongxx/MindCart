package com.smartore.ai.mq;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.smartore.ai.config.AiRabbitTopology;
import com.smartore.ai.entity.TradeEventMirror;
import com.smartore.ai.mapper.TradeEventMirrorMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 交易事件镜像消费者：把订单支付/取消事件落到 AI 库，
 * 形成「trade 事件账本 → MQ → ai 镜像」的第三方账本，供对账脚本核对投递完整性。
 * event_id 唯一约束保证至少一次投递下的幂等。
 */
@Component
public class TradeEventMirrorConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeEventMirrorConsumer.class);

    private final TradeEventMirrorMapper mirrorMapper;

    public TradeEventMirrorConsumer(TradeEventMirrorMapper mirrorMapper) {
        this.mirrorMapper = mirrorMapper;
    }

    @RabbitListener(queues = AiRabbitTopology.QUEUE_TRADE_EVENT)
    public void onMessage(String payload, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            JSONObject event = JSONUtil.parseObj(payload);
            TradeEventMirror mirror = new TradeEventMirror();
            mirror.setEventId(event.getStr("eventId"));
            mirror.setEventType(event.getStr("eventType"));
            mirror.setOrderNo(event.getStr("orderNo"));
            mirror.setUserId(event.getInt("userId"));
            mirror.setPayload(payload);
            mirror.setConsumeTime(cn.hutool.core.date.DateUtil.now());
            mirrorMapper.insertIgnore(mirror);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 解析失败的消息是毒丸：直接 ack 落死信由人工排查，不无限重投
            log.error("交易事件镜像消费失败，消息转死信：{}", e.getMessage());
            channel.basicReject(deliveryTag, false);
        }
    }
}
