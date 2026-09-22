package com.mindcart.ai.mapper;

import com.mindcart.ai.entity.AgentStep;
import java.util.List;

public interface AgentStepMapper {

    int insert(AgentStep agentStep);

    void updateById(AgentStep agentStep);

    void deleteById(Integer id);

    void deleteByRunId(Integer runId);

    List<AgentStep> selectAll(AgentStep agentStep);
}
