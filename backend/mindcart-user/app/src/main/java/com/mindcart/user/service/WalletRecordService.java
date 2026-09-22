package com.mindcart.user.service;

import cn.hutool.core.util.ObjectUtil;
import com.mindcart.common.context.UserContext;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.common.util.BizNoGenerator;
import com.mindcart.user.entity.User;
import com.mindcart.user.entity.WalletRechargeRequest;
import com.mindcart.user.entity.WalletRecord;
import com.mindcart.user.mapper.UserMapper;
import com.mindcart.user.mapper.WalletRecordMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户侧钱包操作（充值、查流水）。资金 Saga 步骤见 WalletSagaService。
 */
@Service
public class WalletRecordService {

    /** 演示环境无支付网关回调，充值是模拟入账：设单笔上限防"自我铸币"无限放大 */
    private static final BigDecimal MAX_RECHARGE = new BigDecimal("10000");

    @Resource
    private UserMapper userMapper;
    @Resource
    private WalletRecordMapper walletRecordMapper;

    @Transactional
    public User recharge(WalletRechargeRequest request) {
        Integer userId = UserContext.requireUserId();
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        if (request.getAmount().compareTo(MAX_RECHARGE) > 0) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "单笔充值上限 " + MAX_RECHARGE.toPlainString() + " 元");
        }
        // 行锁串行化同用户的并发充值：balance_after 在锁内基于最新余额计算，流水链严格连续
        User user = userMapper.selectByIdForUpdate(userId);
        if (ObjectUtil.isNull(user)) {
            throw new CustomException(ResultCodeEnum.USER_NOT_EXIST_ERROR);
        }
        String businessNo = cn.hutool.core.util.StrUtil.isBlank(request.getRequestId())
                ? BizNoGenerator.next("RC")
                : "RC:" + request.getRequestId();
        // 幂等：同一 requestId 双击/重试只入账一次，重复请求直接返回当前余额
        if (walletRecordMapper.selectByBizNoAndType(businessNo, "RECHARGE") != null) {
            return sanitize(userMapper.selectById(userId));
        }
        userMapper.increaseBalance(userId, request.getAmount());

        WalletRecord record = new WalletRecord();
        record.setUserId(userId);
        record.setType("RECHARGE");
        record.setAmount(request.getAmount());
        record.setBalanceAfter(user.getBalance().add(request.getAmount()));
        record.setBusinessNo(businessNo);
        record.setRemark(ObjectUtil.isEmpty(request.getRemark()) ? "用户钱包充值" : request.getRemark());
        record.setCreateTime(cn.hutool.core.date.DateUtil.now());
        walletRecordMapper.insert(record);
        return sanitize(userMapper.selectById(userId));
    }

    public List<WalletRecord> selectOwnRecords() {
        return walletRecordMapper.selectByUserId(UserContext.requireUserId());
    }

    private User sanitize(User user) {
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }
}
