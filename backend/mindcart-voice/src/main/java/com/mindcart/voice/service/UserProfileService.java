package com.mindcart.voice.service;

import com.mindcart.voice.dto.UserProfileSnapshot;
import com.mindcart.voice.entity.UserProfileDynamicEntity;
import com.mindcart.voice.repository.UserProfileDynamicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户画像加载。MindCart 用户体系没有性别/身高/肤质等静态画像字段，
 * 静态部分恒为 null（下游重排对 null 安全降级）；动态画像（行为累加）仍在本地。
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserProfileDynamicRepository dynRepo;

    @Cacheable(value = "userProfileSnapshot", key = "#userId")
    public UserProfileSnapshot load(Long userId) {
        Optional<UserProfileDynamicEntity> d = dynRepo.findById(userId);

        return new UserProfileSnapshot(
                userId,
                null, null, null, null, null, null,
                d.map(UserProfileDynamicEntity::getCategoryAffinity).orElse(Map.of()),
                d.map(UserProfileDynamicEntity::getBrandAffinity).orElse(Map.of()),
                d.map(UserProfileDynamicEntity::getRecentViewed).orElse(List.of()),
                d.map(UserProfileDynamicEntity::getRecentPurchased).orElse(List.of()),
                d.map(UserProfileDynamicEntity::getPriceSensitivity).orElse(null),
                d.map(UserProfileDynamicEntity::getAvgOrderAmount).orElse(null)
        );
    }
}
