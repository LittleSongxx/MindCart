package com.mindcart.ai.mapper;

import com.mindcart.ai.entity.AiModelConfig;
import java.util.List;

public interface AiModelConfigMapper {

    int insert(AiModelConfig aiModelConfig);

    void updateById(AiModelConfig aiModelConfig);

    void deleteById(Integer id);

    @org.apache.ibatis.annotations.Select("select * from ai_model_config where id = #{id}")
    AiModelConfig selectById(Integer id);

    List<AiModelConfig> selectAll(AiModelConfig aiModelConfig);

    /** 同类型互斥启用：除指定行外全部禁用（单条原子 UPDATE） */
    @org.apache.ibatis.annotations.Update("update ai_model_config set is_enabled = 0, update_time = now() "
            + "where model_type = #{modelType} and id != #{keepId} and is_enabled = 1")
    int disableOthersOfModelType(@org.apache.ibatis.annotations.Param("modelType") String modelType,
                                 @org.apache.ibatis.annotations.Param("keepId") Integer keepId);
}
