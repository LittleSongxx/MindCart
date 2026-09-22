package com.mindcart.trade.mapper;

import com.mindcart.trade.entity.ShoppingCart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ShoppingCartMapper {

    int insert(ShoppingCart shoppingCart);

    void updateById(ShoppingCart shoppingCart);

    void deleteById(Integer id);

    @Select("select * from shopping_cart where user_id = #{userId} and product_id = #{productId} limit 1")
    ShoppingCart selectByUserIdAndProductId(@Param("userId") Integer userId, @Param("productId") Integer productId);


    @Select("select * from shopping_cart where user_id = #{userId} order by id desc")
    List<ShoppingCart> selectByUserId(@Param("userId") Integer userId);

    /** 只删自己的选中项（下单成功清车用） */
    @Delete("""
            <script>
            delete from shopping_cart where user_id = #{userId} and selected = 1 and id in
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteSelectedByIds(@Param("userId") Integer userId, @Param("ids") List<Integer> ids);

    @Select("select count(*) from shopping_cart where user_id = #{userId} and id = #{id}")
    int countOwned(@Param("userId") Integer userId, @Param("id") Integer id);
}
