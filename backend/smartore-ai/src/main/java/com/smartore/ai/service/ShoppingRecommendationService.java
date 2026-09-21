package com.smartore.ai.service;

import cn.hutool.core.date.DateUtil;
import com.smartore.ai.entity.ShoppingRecommendation;
import com.smartore.ai.mapper.ShoppingRecommendationMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ShoppingRecommendationService {

    @Resource
    private NameFillService nameFillService;

    @Resource
    private ShoppingRecommendationMapper shoppingRecommendationMapper;

    /** delete+insert 重写必须在同一事务：交错失败会留下旧推荐与新推荐并存的混合状态 */
    @Transactional
    public void saveTaskRecommendations(Integer taskId, List<ShoppingRecommendation> recommendations) {
        shoppingRecommendationMapper.deleteByTaskId(taskId);
        if (recommendations == null || recommendations.isEmpty()) {
            return;
        }
        String now = DateUtil.now();
        for (ShoppingRecommendation recommendation : recommendations) {
            recommendation.setStatus("VALID");
            recommendation.setCreateTime(now);
            recommendation.setUpdateTime(now);
            shoppingRecommendationMapper.insert(recommendation);
        }
    }

    public void deleteById(Integer id) {
        shoppingRecommendationMapper.deleteById(id);
    }

    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            shoppingRecommendationMapper.deleteById(id);
        }
    }

    public List<ShoppingRecommendation> selectAll(ShoppingRecommendation condition) {
        List<ShoppingRecommendation> list = shoppingRecommendationMapper.selectAll(condition);
        nameFillService.fillUserNames(list, ShoppingRecommendation::getUserId, ShoppingRecommendation::setUserName);
        return list;
    }

    public PageInfo<ShoppingRecommendation> selectPage(ShoppingRecommendation shoppingRecommendation, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<ShoppingRecommendation> list = shoppingRecommendationMapper.selectAll(shoppingRecommendation);
        return PageInfo.of(list);
    }
}
