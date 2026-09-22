package com.mindcart.goods.entity;

/** 库存变动流水：biz_no+product_id+change_type 唯一，是库存幂等扣减与对账的依据 */
public class StockChangeRecord {

    public static final String TYPE_DEDUCT = "DEDUCT";
    public static final String TYPE_RESTORE = "RESTORE";

    private Integer id;
    private String bizNo;
    private Integer productId;
    private String changeType;
    private Integer quantity;
    private String createTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public Integer getProductId() {
        return productId;
    }

    public void setProductId(Integer productId) {
        this.productId = productId;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }
}
