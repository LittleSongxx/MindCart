package com.smartore.goods.mapper;

import com.smartore.goods.entity.StockChangeRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface StockChangeRecordMapper {

    /** 幂等占位：唯一键 (biz_no, product_id, change_type) 冲突返回 0 */
    @org.apache.ibatis.annotations.Insert("""
            insert ignore into stock_change_record (biz_no, product_id, change_type, quantity, create_time)
            values (#{bizNo}, #{productId}, #{changeType}, #{quantity}, now())
            """)
    int insertIgnore(StockChangeRecord record);

    @Select("select * from stock_change_record where biz_no = #{bizNo} and change_type = #{changeType}")
    List<StockChangeRecord> selectByBizNoAndType(@Param("bizNo") String bizNo, @Param("changeType") String changeType);
}
