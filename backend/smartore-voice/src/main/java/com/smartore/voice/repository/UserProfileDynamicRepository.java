package com.smartore.voice.repository;

import com.smartore.voice.entity.UserProfileDynamicEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserProfileDynamicRepository extends JpaRepository<UserProfileDynamicEntity, Long> {
}