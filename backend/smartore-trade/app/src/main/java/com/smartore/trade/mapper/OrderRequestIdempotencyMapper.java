package com.smartore.trade.mapper;

import com.smartore.trade.entity.OrderRequestIdempotency;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OrderRequestIdempotencyMapper {

    @Insert("""
            insert ignore into order_request_idempotency
            (request_id, request_hash, order_no, status, create_time, update_time)
            values (#{requestId}, #{requestHash}, #{orderNo}, #{status}, now(), now())
            """)
    int insertIgnore(OrderRequestIdempotency record);

    @Select("select * from order_request_idempotency where request_id = #{requestId} limit 1")
    OrderRequestIdempotency selectByRequestId(@Param("requestId") String requestId);

    @Select("select * from order_request_idempotency where order_no = #{orderNo} limit 1")
    OrderRequestIdempotency selectByOrderNo(@Param("orderNo") String orderNo);

    @Update("update order_request_idempotency set order_id = #{orderId}, status = #{status}, update_time = now() where request_id = #{requestId}")
    int bindOrder(@Param("requestId") String requestId, @Param("orderId") Integer orderId, @Param("status") String status);

    @Update("update order_request_idempotency set status = #{status}, update_time = now() where request_id = #{requestId}")
    int updateStatus(@Param("requestId") String requestId, @Param("status") String status);
}
