package com.smartore.user.mapper;

import com.smartore.user.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

public interface UserMapper {

    int insert(User user);

    void updateById(User user);

    void deleteById(Integer id);

    @Select("select * from `user` where id = #{id}")
    User selectById(Integer id);

    /** 当前读（FOR UPDATE）：资金类流水计算 balance_after 时取最新已提交余额，保证流水链严格连续 */
    @Select("select * from `user` where id = #{id} for update")
    User selectByIdForUpdate(Integer id);

    @Select("select * from `user` where username = #{username}")
    User selectByUsername(String username);

    @Select("select * from `user` where role = 'ADMIN' order by id asc limit 1")
    User selectFirstAdmin();

    @Update("update `user` set balance = ifnull(balance, 0) + #{amount} where id = #{userId}")
    void increaseBalance(@Param("userId") Integer userId, @Param("amount") BigDecimal amount);

    @Update("update `user` set balance = ifnull(balance, 0) - #{amount} where id = #{userId} and ifnull(balance, 0) >= #{amount}")
    int decreaseBalance(@Param("userId") Integer userId, @Param("amount") BigDecimal amount);

    List<User> selectAll(User user);

}
