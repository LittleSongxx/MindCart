package com.mindcart.ai.service;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mindcart.ai.mq.GuideTaskPublisher;
import com.mindcart.ai.entity.ShoppingGuideTask;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.common.util.BizNoGenerator;
import com.mindcart.ai.mapper.ShoppingGuideTaskMapper;
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

    /** 创建任务并立即入队异步执行；返回带 id 的任务实体（前端需要 id 做轮询） */
    public ShoppingGuideTask add(ShoppingGuideTask task) {
        // 任务归属只认当前登录用户（需求文本/预算属个人信息，且任务会被回填真实姓名）
        task.setUserId(com.mindcart.common.context.UserContext.requireUserId());
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
        return task;
    }

    public void updateById(ShoppingGuideTask task) {
        if (ObjectUtil.isEmpty(task.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        validate(task);
        ShoppingGuideTask existing = shoppingGuideTaskMapper.selectById(task.getId());
        if (ObjectUtil.isNull(existing)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        requireOwnedOrAdmin(existing);
        // RUNNING 中禁止重置重发：兜底扫描的超时重发窗口内，重置会导致同任务并发双跑
        if ("RUNNING".equals(existing.getStatus())) {
            throw new CustomException(ResultCodeEnum.ORDER_STATUS_ERROR, "任务执行中，请等待完成后再修改");
        }
        // 只更新任务内容字段：userId/状态/结果字段保持库内值，防请求体篡改归属
        task.setUserId(existing.getUserId());
        task.setTaskNo(existing.getTaskNo());
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
        requireOwnedOrAdmin(task);
        task.setUpdateTime(cn.hutool.core.date.DateUtil.now());
        task.setStatus("WAITING");
        shoppingGuideTaskMapper.updateById(task);
        guideTaskPublisher.publish(task.getId());
    }

    public void deleteById(Integer id) {
        ShoppingGuideTask task = shoppingGuideTaskMapper.selectById(id);
        if (task != null) {
            requireOwnedOrAdmin(task);
        }
        shoppingGuideTaskMapper.deleteById(id);
    }

    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            deleteById(id);
        }
    }

    private void requireOwnedOrAdmin(ShoppingGuideTask task) {
        if (!com.mindcart.common.context.UserContext.isAdmin()
                && !task.getUserId().equals(com.mindcart.common.context.UserContext.requireUserId())) {
            throw new CustomException(ResultCodeEnum.FORBIDDEN);
        }
    }

    public List<ShoppingGuideTask> selectAll(ShoppingGuideTask task) {
        visibleCondition(task);
        List<ShoppingGuideTask> list = shoppingGuideTaskMapper.selectAll(task);
        nameFillService.fillUserNames(list, ShoppingGuideTask::getUserId, ShoppingGuideTask::setUserName);
        nameFillService.fillProducts(list, ShoppingGuideTask::getProductId, (row, p) -> row.setProductName(p.getName()));
        return list;
    }

    public PageInfo<ShoppingGuideTask> selectPage(ShoppingGuideTask task, Integer pageNum, Integer pageSize) {
        visibleCondition(task);
        PageHelper.startPage(pageNum, pageSize);
        List<ShoppingGuideTask> list = shoppingGuideTaskMapper.selectAll(task);
        nameFillService.fillUserNames(list, ShoppingGuideTask::getUserId, ShoppingGuideTask::setUserName);
        return PageInfo.of(list);
    }

    /** 普通用户只能看自己的任务（需求文本/预算/回填姓名属个人信息） */
    private void visibleCondition(ShoppingGuideTask task) {
        if (!com.mindcart.common.context.UserContext.isAdmin()) {
            task.setUserId(com.mindcart.common.context.UserContext.requireUserId());
        }
    }

    private void validate(ShoppingGuideTask task) {
        if (ObjectUtil.isNull(task) || StrUtil.isBlank(task.getDemandText())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
