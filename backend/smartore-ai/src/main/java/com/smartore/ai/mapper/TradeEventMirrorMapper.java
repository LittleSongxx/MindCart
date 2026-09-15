package com.smartore.ai.mapper;

import com.smartore.ai.entity.TradeEventMirror;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TradeEventMirrorMapper {

    /** event_id 唯一：MQ 至少一次投递下重复消费安全 */
    @Insert("""
            insert ignore into trade_event_mirror
            (event_id, event_type, order_no, user_id, payload, consume_time)
            values (#{eventId}, #{eventType}, #{orderNo}, #{userId}, #{payload}, #{consumeTime})
            """)
    int insertIgnore(TradeEventMirror mirror);

    @Select("select * from trade_event_mirror order by id desc limit #{limit}")
    List<TradeEventMirror> selectRecent(int limit);
}
