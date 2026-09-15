package com.smartore.trade.mapper;

import com.smartore.trade.entity.ShopOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ShopOrderMapper {

    int insert(ShopOrder shopOrder);

    void updateById(ShopOrder shopOrder);

    ShopOrder selectById(Integer id);

    List<ShopOrder> selectAll(ShopOrder shopOrder);

    void deleteById(Integer id);

    @Select("select * from shop_order where order_no = #{orderNo} limit 1")
    ShopOrder selectByOrderNo(@Param("orderNo") String orderNo);

    // ---- 状态机：全部条件 UPDATE，影响行数=0 即并发冲突/状态已变 ----

    @Update("update shop_order set status = 'PAID', pay_time = now(), update_time = now() "
            + "where id = #{id} and status = 'PAYING'")
    int markPaid(@Param("id") Integer id);

    @Update("update shop_order set status = 'PAY_FAILED', update_time = now() "
            + "where id = #{id} and status = 'PAYING'")
    int markPayFailed(@Param("id") Integer id);

    @Update("update shop_order set status = 'CANCELLING', update_time = now() "
            + "where id = #{id} and status = 'PAID'")
    int beginCancel(@Param("id") Integer id);

    @Update("update shop_order set status = 'CANCELLED', cancel_time = now(), update_time = now() "
            + "where id = #{id} and status in ('CANCELLING','PAID')")
    int finishCancel(@Param("id") Integer id);

    @Update("update shop_order set status = 'SHIPPED', ship_time = now(), update_time = now() "
            + "where id = #{id} and status = 'PAID'")
    int markShipped(@Param("id") Integer id);

    @Update("update shop_order set status = 'COMPLETED', finish_time = now(), update_time = now() "
            + "where id = #{id} and status = 'SHIPPED'")
    int markCompleted(@Param("id") Integer id);

    @Select("select * from shop_order where status = 'PAYING' and update_time < date_sub(now(), interval #{seconds} second) order by id asc limit 50")
    List<ShopOrder> selectStalePaying(@Param("seconds") Integer seconds);

    @Select("select * from shop_order where status = 'CANCELLING' and update_time < date_sub(now(), interval #{seconds} second) order by id asc limit 50")
    List<ShopOrder> selectStaleCancelling(@Param("seconds") Integer seconds);
}
