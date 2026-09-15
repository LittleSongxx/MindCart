package com.smartore.trade.mapper;

import com.smartore.trade.entity.TradeEventLedger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TradeEventLedgerMapper {

    /** 与订单状态变更同事务追加；event_id 唯一 → 重复追加幂等 */
    @Insert("""
            insert ignore into trade_event_ledger
            (event_id, event_type, order_no, user_id, payload, publish_status, publish_attempts, create_time)
            values (#{eventId}, #{eventType}, #{orderNo}, #{userId}, #{payload}, 'PENDING', 0, now())
            """)
    int insertIgnore(TradeEventLedger record);

    @Select("select * from trade_event_ledger where publish_status = 'PENDING' and publish_attempts < 10 order by id asc limit 50")
    List<TradeEventLedger> selectPending();

    @Update("update trade_event_ledger set publish_status = 'PUBLISHED', publish_time = now() where id = #{id}")
    int markPublished(@Param("id") Integer id);

    @Update("update trade_event_ledger set publish_attempts = publish_attempts + 1 where id = #{id}")
    int increaseAttempts(@Param("id") Integer id);

    @Select("select * from trade_event_ledger where order_no = #{orderNo} and publish_status = 'PUBLISHED'")
    List<TradeEventLedger> selectPublishedByOrderNo(@Param("orderNo") String orderNo);
}
