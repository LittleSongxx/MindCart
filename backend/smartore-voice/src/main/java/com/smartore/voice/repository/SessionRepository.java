package com.smartore.voice.repository;

import com.smartore.voice.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    /**
     * 累加会话 token 用量：并发 UPDATE 由行锁串行化，不存在读-改-写竞态。
     * 独立事务（REQUIRES_NEW 由调用方异步触发，主流程失败不影响 token 记账的回滚边界）
     */
    @Modifying
    @Transactional
    @Query("UPDATE SessionEntity s SET s.totalTokens = s.totalTokens + :delta " +
            "WHERE s.id = :sessionId")
    int addTotalTokens(@Param("sessionId") String sessionId, @Param("delta") int delta);
}