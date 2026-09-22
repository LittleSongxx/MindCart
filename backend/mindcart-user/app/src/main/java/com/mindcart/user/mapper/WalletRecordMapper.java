package com.mindcart.user.mapper;

import com.mindcart.user.entity.WalletRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface WalletRecordMapper {

    int insert(WalletRecord walletRecord);

    /** 幂等占位插入：唯一键 (business_no, type) 冲突时返回 0，不抛异常 */
    int insertIgnore(WalletRecord walletRecord);

    @Select("""
            select wr.*, u.name as userName
            from wallet_record wr
            left join `user` u on wr.user_id = u.id
            where wr.user_id = #{userId}
            order by wr.id desc
            """)
    List<WalletRecord> selectByUserId(Integer userId);

    @Select("select * from wallet_record where business_no = #{businessNo} and type = #{type} limit 1")
    WalletRecord selectByBizNoAndType(@Param("businessNo") String businessNo, @Param("type") String type);

    @Update("update wallet_record set balance_after = #{balanceAfter} where id = #{id}")
    int updateBalanceAfter(@Param("id") Integer id, @Param("balanceAfter") java.math.BigDecimal balanceAfter);

    @Delete("delete from wallet_record where user_id = #{userId}")
    int deleteByUserId(Integer userId);
}
