package com.mindcart.ai.mapper;

import com.mindcart.ai.entity.QaConversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface QaConversationMapper {

    @Insert("""
            insert into qa_conversation (user_id, title, create_time, update_time)
            values (#{userId}, #{title}, now(), now())
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(QaConversation conversation);

    @Select("select * from qa_conversation where id = #{id} limit 1")
    QaConversation selectById(@Param("id") Integer id);

    @Select("select * from qa_conversation where user_id = #{userId} order by id desc")
    List<QaConversation> selectByUserId(@Param("userId") Integer userId);

    @Update("update qa_conversation set update_time = now() where id = #{id}")
    int touch(@Param("id") Integer id);
}
