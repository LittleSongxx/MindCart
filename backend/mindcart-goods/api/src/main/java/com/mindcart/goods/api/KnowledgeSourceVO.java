package com.mindcart.goods.api;

import java.util.List;

/** 商品知识导入源：商品详情 + 规格参数的聚合视图（AI 知识库一键导入用） */
public class KnowledgeSourceVO {

    private Integer productId;
    private String productName;
    private String detailContent;
    private String packageInfo;
    private String afterSaleInfo;
    private List<ParamEntry> params;

    public static class ParamEntry {
        private String paramGroup;
        private String paramName;
        private String paramValue;

        public String getParamGroup() {
            return paramGroup;
        }

        public void setParamGroup(String paramGroup) {
            this.paramGroup = paramGroup;
        }

        public String getParamName() {
            return paramName;
        }

        public void setParamName(String paramName) {
            this.paramName = paramName;
        }

        public String getParamValue() {
            return paramValue;
        }

        public void setParamValue(String paramValue) {
            this.paramValue = paramValue;
        }
    }

    public Integer getProductId() {
        return productId;
    }

    public void setProductId(Integer productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getDetailContent() {
        return detailContent;
    }

    public void setDetailContent(String detailContent) {
        this.detailContent = detailContent;
    }

    public String getPackageInfo() {
        return packageInfo;
    }

    public void setPackageInfo(String packageInfo) {
        this.packageInfo = packageInfo;
    }

    public String getAfterSaleInfo() {
        return afterSaleInfo;
    }

    public void setAfterSaleInfo(String afterSaleInfo) {
        this.afterSaleInfo = afterSaleInfo;
    }

    public List<ParamEntry> getParams() {
        return params;
    }

    public void setParams(List<ParamEntry> params) {
        this.params = params;
    }
}
