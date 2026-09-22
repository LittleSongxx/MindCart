package com.smartore.trade.mapper;

import com.smartore.trade.entity.ShopOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ShopOrderMapper {

    int insert(ShopOrder shopOrder);

    void updateById(ShopOrder shopOrder);

    @Select("select * from shop_order where id = #{id}")
    ShopOrder selectById(Integer id);

    List<ShopOrder> selectAll(ShopOrder shopOrder);

    // 刻意不提供 delete：订单是账务事实，只走状态机（CANCELLED/PAY_FAILED）流转。
    // （这里原本声明过一个无注解也无 XML 的 deleteById，一旦被调用就会抛 BindingException，
    //   已删除；MapperWiringTest 会持续守住"声明的方法都有绑定"。）

    @Select("select * from shop_order where order_no = #{orderNo} limit 1")
    ShopOrder selectByOrderNo(@Param("orderNo") String orderNo);

    // ---- 状态机：全部条件 UPDATE，影响行数=0 即并发冲突/状态已变（转移表见 OrderStatus 枚举）----

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
            + "where id = #{id} and status = 'CANCELLING'")
    int finishCancel(@Param("id") Integer id);

    /** 反向退款收口：迟到提交的扣款已退回后，PAY_FAILED → CANCELLED（与枚举转移表一致） */
    @Update("update shop_order set status = 'CANCELLED', cancel_time = now(), update_time = now() "
            + "where id = #{id} and status = 'PAY_FAILED'")
    int markCancelledFromPayFailed(@Param("id") Integer id);

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

    /** PAY_FAILED 兜底扫描：正常应查无结果——若钱包侧存在 PAY 流水即为资错，需自动反向退款 */
    @Select("select * from shop_order where status = 'PAY_FAILED' and update_time < date_sub(now(), interval #{seconds} second) order by id asc limit 50")
    List<ShopOrder> selectStalePayFailed(@Param("seconds") Integer seconds);
}
