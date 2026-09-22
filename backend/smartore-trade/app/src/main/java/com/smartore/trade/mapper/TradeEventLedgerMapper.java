package com.smartore.trade.mapper;

import com.smartore.trade.entity.TradeEventLedger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface TradeEventLedgerMapper {

    /** 与订单状态变更同事务追加；event_id 唯一 → 重复追加幂等 */
    @Insert("""
            insert ignore into trade_event_ledger
            (event_id, event_type, order_no, user_id, payload, publish_status, publish_attempts, create_time)
            values (#{eventId}, #{eventType}, #{orderNo}, #{userId}, #{payload}, 'PENDING', 0, now())
            """)
    int insertIgnore(TradeEventLedger record);

    /**
     * 待投递事件：退避时间已到才捞出来（next_retry_at 为空表示立即可投）。
     * 此前没有退避条件，broker 故障时每 3 秒会把同一批必然失败的事件硬拍一遍。
     */
    @Select("""
            select * from trade_event_ledger
            where publish_status = 'PENDING' and publish_attempts < #{maxAttempts}
              and (next_retry_at is null or next_retry_at <= now())
            order by id asc limit #{batchSize}
            """)
    List<TradeEventLedger> selectPending(@Param("maxAttempts") int maxAttempts,
                                         @Param("batchSize") int batchSize);

    @Update("update trade_event_ledger set publish_status = 'PUBLISHED', publish_time = now() where id = #{id}")
    int markPublished(@Param("id") Integer id);

    /** 投递失败：累加次数并按退避表推后下次重试时间（退避值由服务层算好传入，避免 SQL 里写死策略） */
    @Update("""
            update trade_event_ledger
            set publish_attempts = publish_attempts + 1,
                next_retry_at = date_add(now(), interval #{delaySeconds} second)
            where id = #{id}
            """)
    int increaseAttempts(@Param("id") Integer id, @Param("delaySeconds") int delaySeconds);

    /** 重试耗尽：PENDING→FAILED（返回条数供告警日志）；人工处置后用 replayExhausted 复位重投 */
    @Update("update trade_event_ledger set publish_status = 'FAILED' where publish_status = 'PENDING' and publish_attempts >= #{maxAttempts}")
    int markFailedExhausted(@Param("maxAttempts") int maxAttempts);

    /** 人工复位：FAILED→PENDING 且清零次数与退避，让中继重新尝试（取代过去只能手写 SQL） */
    @Update("""
            update trade_event_ledger
            set publish_status = 'PENDING', publish_attempts = 0, next_retry_at = null
            where publish_status = 'FAILED'
            """)
    int replayExhausted();

    /** 账本水位：PENDING/FAILED/PUBLISHED 各多少，供告警与运维看板使用 */
    @Select("select publish_status as status, count(*) as total from trade_event_ledger group by publish_status")
    List<Map<String, Object>> countByStatus();

    @Select("select * from trade_event_ledger where order_no = #{orderNo} and publish_status = 'PUBLISHED'")
    List<TradeEventLedger> selectPublishedByOrderNo(@Param("orderNo") String orderNo);
}
