package com.smartore.ai.service;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.smartore.ai.mq.GuideTaskPublisher;
import com.smartore.ai.entity.ShoppingGuideTask;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.common.util.BizNoGenerator;
import com.smartore.ai.mapper.ShoppingGuideTaskMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 导购任务：CRUD + 异步提交。
 *
 * 单体版在 HTTP 线程里同步跑完多轮 LLM 调用（前端 30s 超时必然踩雷）；
 * 现在提交即入队（RabbitMQ），消费者执行 Agent 循环，前端轮询任务状态与 AgentStep 轨迹。
 * 任务表本身就是可靠队列（WAITING 状态由兜底扫描重新入队），消息丢失不丢任务。
 */
@Service
public class ShoppingGuideTaskService {

    @Resource
    private NameFillService nameFillService;

    @Resource
    private ShoppingGuideTaskMapper shoppingGuideTaskMapper;
    @Resource
    private GuideTaskPublisher guideTaskPublisher;

    public void add(ShoppingGuideTask task) {
        validate(task);
        if (ObjectUtil.isEmpty(task.getTaskNo())) {
            task.setTaskNo(BizNoGenerator.next("GT"));
        }
        if (ObjectUtil.isNotNull(shoppingGuideTaskMapper.selectByTaskNo(task.getTaskNo()))) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        String now = cn.hutool.core.date.DateUtil.now();
        task.setStatus("WAITING");
        task.setCreateTime(now);
        task.setUpdateTime(now);
        shoppingGuideTaskMapper.insert(task);
        // 创建即提交执行（异步）
        submit(task.getId());
    }

    public void updateById(ShoppingGuideTask task) {
        if (ObjectUtil.isEmpty(task.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        validate(task);
        if (ObjectUtil.isNull(shoppingGuideTaskMapper.selectById(task.getId()))) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        task.setStatus("WAITING");
        task.setMatchedProductIds("");
        task.setRecommendationResult("");
        task.setExecuteMessage("");
        task.setUpdateTime(cn.hutool.core.date.DateUtil.now());
        shoppingGuideTaskMapper.updateById(task);
        submit(task.getId());
    }

    /** 提交执行：入队即返回。重复提交安全（消费者以任务当前状态为准） */
    public void submit(Integer id) {
        ShoppingGuideTask task = shoppingGuideTaskMapper.selectById(id);
        if (ObjectUtil.isNull(task)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        task.setUpdateTime(cn.hutool.core.date.DateUtil.now());
        task.setStatus("WAITING");
        shoppingGuideTaskMapper.updateById(task);
        guideTaskPublisher.publish(task.getId());
    }

    public void deleteById(Integer id) {
        shoppingGuideTaskMapper.deleteById(id);
    }

    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            shoppingGuideTaskMapper.deleteById(id);
        }
    }

    public List<ShoppingGuideTask> selectAll(ShoppingGuideTask task) {
        List<ShoppingGuideTask> list = shoppingGuideTaskMapper.selectAll(task);
        nameFillService.fillUserNames(list, ShoppingGuideTask::getUserId, ShoppingGuideTask::setUserName);
        nameFillService.fillProducts(list, ShoppingGuideTask::getProductId, (row, p) -> row.setProductName(p.getName()));
        return list;
    }

    public PageInfo<ShoppingGuideTask> selectPage(ShoppingGuideTask task, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(shoppingGuideTaskMapper.selectAll(task));
    }

    private void validate(ShoppingGuideTask task) {
        if (ObjectUtil.isNull(task) || StrUtil.isBlank(task.getDemandText())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
