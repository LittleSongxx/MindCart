package com.smartore.user.service;

import cn.hutool.core.util.ObjectUtil;
import com.smartore.common.context.UserContext;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.common.util.BizNoGenerator;
import com.smartore.user.entity.User;
import com.smartore.user.entity.WalletRechargeRequest;
import com.smartore.user.entity.WalletRecord;
import com.smartore.user.mapper.UserMapper;
import com.smartore.user.mapper.WalletRecordMapper;
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
        User user = userMapper.selectById(userId);
        if (ObjectUtil.isNull(user)) {
            throw new CustomException(ResultCodeEnum.USER_NOT_EXIST_ERROR);
        }
        userMapper.increaseBalance(userId, request.getAmount());

        WalletRecord record = new WalletRecord();
        record.setUserId(userId);
        record.setType("RECHARGE");
        record.setAmount(request.getAmount());
        record.setBalanceAfter(userMapper.selectById(userId).getBalance());
        record.setBusinessNo(BizNoGenerator.next("RC"));
        record.setRemark(ObjectUtil.isEmpty(request.getRemark()) ? "用户钱包充值" : request.getRemark());
        record.setCreateTime(cn.hutool.core.date.DateUtil.now());
        walletRecordMapper.insert(record);
        User updated = userMapper.selectById(userId);
        updated.setPassword(null);
        return updated;
    }

    public List<WalletRecord> selectOwnRecords() {
        return walletRecordMapper.selectByUserId(UserContext.requireUserId());
    }
}
