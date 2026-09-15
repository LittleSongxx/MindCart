package com.smartore.ai.mapper;

import com.smartore.ai.entity.ShoppingGuideTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ShoppingGuideTaskMapper {

    int insert(ShoppingGuideTask shoppingGuideTask);

    void updateById(ShoppingGuideTask shoppingGuideTask);

    void deleteById(Integer id);

    @Select("select * from shopping_guide_task where id = #{id}")
    ShoppingGuideTask selectById(Integer id);

    @Select("select * from shopping_guide_task where task_no = #{taskNo} limit 1")
    ShoppingGuideTask selectByTaskNo(@Param("taskNo") String taskNo);

    List<ShoppingGuideTask> selectAll(ShoppingGuideTask shoppingGuideTask);

    /** WAITING 超时未执行的任务（兜底重发扫描） */
    @Select("select * from shopping_guide_task where status = 'WAITING' "
            + "and update_time < date_sub(now(), interval #{seconds} second) order by id asc limit 20")
    List<ShoppingGuideTask> selectStaleWaiting(@Param("seconds") Integer seconds);

    /** 指定状态超时的任务（RUNNING 兜底重置用） */
    @Select("select * from shopping_guide_task where status = #{status} "
            + "and update_time < date_sub(now(), interval #{seconds} second) order by id asc limit 20")
    List<ShoppingGuideTask> selectStaleByStatus(@Param("status") String status, @Param("seconds") Integer seconds);
}
