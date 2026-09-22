package com.mindcart.user.api;

/**
 * 指定订单在钱包侧各 Saga 步骤是否已生效（恢复任务/对账判定用）。
 */
public class WalletStatusVO {

    private boolean paid;
    private boolean income;
    private boolean refunded;
    private boolean refundOut;

    public boolean isPaid() {
        return paid;
    }

    public void setPaid(boolean paid) {
        this.paid = paid;
    }

    public boolean isIncome() {
        return income;
    }

    public void setIncome(boolean income) {
        this.income = income;
    }

    public boolean isRefunded() {
        return refunded;
    }

    public void setRefunded(boolean refunded) {
        this.refunded = refunded;
    }

    public boolean isRefundOut() {
        return refundOut;
    }

    public void setRefundOut(boolean refundOut) {
        this.refundOut = refundOut;
    }
}
