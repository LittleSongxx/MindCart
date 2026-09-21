package com.smartore.voice.repository;

import com.smartore.voice.entity.SessionStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SessionStateRepository extends JpaRepository<SessionStateEntity, String> {
}