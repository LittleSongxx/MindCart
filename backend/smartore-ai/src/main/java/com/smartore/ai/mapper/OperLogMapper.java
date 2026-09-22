package com.smartore.ai.mapper;

import com.smartore.common.audit.OperLogEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 审计落库（ai 库，表结构与 goods 一致，参数直接用 common 的 OperLogEntry） */
public interface OperLogMapper {

    @Insert("""
            insert into oper_log (user_id, role, module, action, request_uri, http_method, client_ip,
                                  args, result, error_msg, cost_ms, create_time)
            values (#{userId}, #{role}, #{module}, #{action}, #{requestUri}, #{httpMethod}, #{clientIp},
                    #{args}, #{result}, #{errorMsg}, #{costMs}, now())
            """)
    int insert(OperLogEntry entry);

    @Select("""
            <script>
            select * from oper_log
            <where>
              <if test="userId != null"> and user_id = #{userId}</if>
              <if test="module != null and module != ''"> and module = #{module}</if>
              <if test="action != null and action != ''"> and action like concat('%', #{action}, '%')</if>
              <if test="result != null and result != ''"> and result = #{result}</if>
            </where>
            order by id desc
            </script>
            """)
    List<OperLogEntry> selectAll(OperLogEntry condition);

    @Select("select count(*) from oper_log where create_time >= date_sub(now(), interval #{minutes} minute)")
    int countRecent(@Param("minutes") Integer minutes);
}
