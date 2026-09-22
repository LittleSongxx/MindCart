package com.smartore.goods.mapper;

import com.smartore.common.audit.OperLogEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 审计落库。直接用 common 的 {@link OperLogEntry} 当参数/结果载体——
 * 各服务的 oper_log 表结构一致，没必要每个服务再维护一份等价的实体类。
 */
public interface OperLogMapper {

    @Insert("""
            insert into oper_log (user_id, role, module, action, request_uri, http_method, client_ip,
                                  args, result, error_msg, cost_ms, create_time)
            values (#{userId}, #{role}, #{module}, #{action}, #{requestUri}, #{httpMethod}, #{clientIp},
                    #{args}, #{result}, #{errorMsg}, #{costMs}, now())
            """)
    int insert(OperLogEntry entry);

    /** 条件查询：审计表只增不改，按 id 倒序即时间倒序，分页由 PageHelper 接管 */
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
