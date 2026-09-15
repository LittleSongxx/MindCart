package com.smartore.ai.mapper;

import com.smartore.ai.entity.AiModelConfig;
import java.util.List;

public interface AiModelConfigMapper {

    int insert(AiModelConfig aiModelConfig);

    void updateById(AiModelConfig aiModelConfig);

    void deleteById(Integer id);

    @org.apache.ibatis.annotations.Select("select * from ai_model_config where id = #{id}")
    AiModelConfig selectById(Integer id);

    List<AiModelConfig> selectAll(AiModelConfig aiModelConfig);
}
