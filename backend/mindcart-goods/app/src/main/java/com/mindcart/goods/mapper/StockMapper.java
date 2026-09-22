package com.mindcart.goods.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface StockMapper {

    /** 管理端直接设置库存（upsert） */
    @Insert("""
            insert into stock (product_id, available, update_time)
            values (#{productId}, #{available}, now())
            on duplicate key update available = values(available), update_time = now()
            """)
    int upsert(@Param("productId") Integer productId, @Param("available") Integer available);

    /** 原子条件扣减：available 不足返回 0 行（防超卖的唯一天然屏障） */
    @Update("update stock set available = available - #{quantity}, update_time = now() "
            + "where product_id = #{productId} and available >= #{quantity}")
    int deductAvailable(@Param("productId") Integer productId, @Param("quantity") Integer quantity);

    @Update("update stock set available = available + #{quantity}, update_time = now() "
            + "where product_id = #{productId}")
    int increaseAvailable(@Param("productId") Integer productId, @Param("quantity") Integer quantity);

    /** 把权威库存同步到商品表展示列（同事务内调用，服务内单库无跨写问题） */
    @Update("update product p join stock s on p.id = s.product_id "
            + "set p.stock_quantity = s.available, p.update_time = now() where p.id = #{productId}")
    int syncProductDisplay(@Param("productId") Integer productId);

    /** 商品被删除时同步清掉库存权威行，避免留下孤儿行（商品 id 复用时会被 upsert 命中而"继承"旧库存） */
    @org.apache.ibatis.annotations.Delete("delete from stock where product_id = #{productId}")
    int deleteByProductId(@Param("productId") Integer productId);

    @Select("select * from stock where product_id = #{productId}")
    com.mindcart.goods.entity.Stock selectByProductId(@Param("productId") Integer productId);

    @Select("""
            <script>
            select * from stock where product_id in
            <foreach collection="productIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<com.mindcart.goods.entity.Stock> selectByProductIds(@Param("productIds") List<Integer> productIds);
}
