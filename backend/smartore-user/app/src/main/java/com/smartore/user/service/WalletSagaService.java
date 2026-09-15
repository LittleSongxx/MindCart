package com.smartore.user.service;

import cn.hutool.core.util.StrUtil;
import com.smartore.user.api.WalletOpRequest;
import com.smartore.user.entity.User;
import com.smartore.user.entity.WalletRecord;
import com.smartore.user.mapper.UserMapper;
import com.smartore.user.mapper.WalletRecordMapper;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.ResultCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 钱包 Saga 步骤（仅交易域经 Feign 调用，路径 /internal/wallet/** 不出网关）。
 *
 * 幂等实现：先 INSERT IGNORE 流水（唯一键 business_no+type），插入成功才动余额；
 * 已存在说明该订单这一步早已生效，直接返回 —— 交易侧重试/恢复任务重放是安全的。
 * 流水与余额变动在同一本地事务内，balance_after 记录变动后余额，天然是对账数据源。
 */
@Service
public class WalletSagaService {

    public static final String TYPE_PAY = "PAY";
    public static final String TYPE_INCOME = "INCOME";
    public static final String TYPE_REFUND = "REFUND";
    public static final String TYPE_REFUND_OUT = "REFUND_OUT";

    @Resource
    private UserMapper userMapper;
    @Resource
    private WalletRecordMapper walletRecordMapper;

    /** 买家支付扣款 */
    @Transactional
    public void payForOrder(WalletOpRequest request) {
        validate(request);
        User buyer = requireUser(request.getUserId());
        if (insertFlowIgnore(request.getOrderNo(), TYPE_PAY, request.getAmount().negate(), buyer.getId(),
                StrUtil.blankToDefault(request.getRemark(), "订单支付"))) {
            int rows = userMapper.decreaseBalance(buyer.getId(), request.getAmount());
            if (rows == 0) {
                throw new CustomException(ResultCodeEnum.BALANCE_NOT_ENOUGH);
            }
            fillBalanceAfter(request.getOrderNo(), TYPE_PAY);
        }
    }

    /** 平台收款（入第一个管理员账户） */
    @Transactional
    public void platformIncome(WalletOpRequest request) {
        validate(request);
        User admin = userMapper.selectFirstAdmin();
        requireUser(admin == null ? null : admin.getId());
        if (insertFlowIgnore(request.getOrderNo(), TYPE_INCOME, request.getAmount(), admin.getId(),
                StrUtil.blankToDefault(request.getRemark(), "订单收入"))) {
            userMapper.increaseBalance(admin.getId(), request.getAmount());
            fillBalanceAfter(request.getOrderNo(), TYPE_INCOME);
        }
    }

    /** 退款给买家：只有该订单真的扣过款（存在 PAY 流水）才生效，否则空返回 —— 补偿永不凭空造钱 */
    @Transactional
    public void refundToUser(WalletOpRequest request) {
        validate(request);
        if (walletRecordMapper.selectByBizNoAndType(request.getOrderNo(), TYPE_PAY) == null) {
            return;
        }
        User buyer = requireUser(request.getUserId());
        if (insertFlowIgnore(request.getOrderNo(), TYPE_REFUND, request.getAmount(), buyer.getId(),
                StrUtil.blankToDefault(request.getRemark(), "订单取消退款"))) {
            userMapper.increaseBalance(buyer.getId(), request.getAmount());
            fillBalanceAfter(request.getOrderNo(), TYPE_REFUND);
        }
    }

    /** 平台退款出款：只有该订单真的收过款（存在 INCOME 流水）才生效 */
    @Transactional
    public void platformRefundOut(WalletOpRequest request) {
        validate(request);
        if (walletRecordMapper.selectByBizNoAndType(request.getOrderNo(), TYPE_INCOME) == null) {
            return;
        }
        User admin = userMapper.selectFirstAdmin();
        requireUser(admin == null ? null : admin.getId());
        if (insertFlowIgnore(request.getOrderNo(), TYPE_REFUND_OUT, request.getAmount().negate(), admin.getId(),
                StrUtil.blankToDefault(request.getRemark(), "订单取消退款支出"))) {
            int rows = userMapper.decreaseBalance(admin.getId(), request.getAmount());
            if (rows == 0) {
                throw new CustomException(ResultCodeEnum.PARAM_ERROR, "平台账户余额不足，无法退款出款");
            }
            fillBalanceAfter(request.getOrderNo(), TYPE_REFUND_OUT);
        }
    }

    /** 供交易侧恢复任务判定该订单钱包侧各步骤是否已生效 */
    public com.smartore.user.api.WalletStatusVO statusOf(String orderNo) {
        com.smartore.user.api.WalletStatusVO status = new com.smartore.user.api.WalletStatusVO();
        status.setPaid(walletRecordMapper.selectByBizNoAndType(orderNo, TYPE_PAY) != null);
        status.setIncome(walletRecordMapper.selectByBizNoAndType(orderNo, TYPE_INCOME) != null);
        status.setRefunded(walletRecordMapper.selectByBizNoAndType(orderNo, TYPE_REFUND) != null);
        status.setRefundOut(walletRecordMapper.selectByBizNoAndType(orderNo, TYPE_REFUND_OUT) != null);
        return status;
    }


    private void validate(WalletOpRequest request) {
        if (request == null
                || StrUtil.isBlank(request.getOrderNo())
                || request.getAmount() == null
                || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }

    private User requireUser(Integer userId) {
        if (userId == null) {
            throw new CustomException(ResultCodeEnum.USER_NOT_EXIST_ERROR);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new CustomException(ResultCodeEnum.USER_NOT_EXIST_ERROR);
        }
        return user;
    }

    /**
     * 幂等占位：同 (business_no, type) 已存在返回 false（本单该步已生效，跳过资金变动）。
     */
    private boolean insertFlowIgnore(String orderNo, String type, BigDecimal signedAmount, Integer userId, String remark) {
        WalletRecord record = new WalletRecord();
        record.setUserId(userId);
        record.setType(type);
        record.setAmount(signedAmount);
        record.setBalanceAfter(BigDecimal.ZERO);
        record.setBusinessNo(orderNo);
        record.setRemark(remark);
        record.setCreateTime(cn.hutool.core.date.DateUtil.now());
        return walletRecordMapper.insertIgnore(record) > 0;
    }

    private void fillBalanceAfter(String orderNo, String type) {
        WalletRecord record = walletRecordMapper.selectByBizNoAndType(orderNo, type);
        Integer userId = record.getUserId();
        User user = userMapper.selectById(userId);
        walletRecordMapper.updateBalanceAfter(record.getId(), user.getBalance());
    }
}
