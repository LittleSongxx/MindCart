package com.smartore.goods.api;

/** 售后规则对外视图（AI 售后问答证据源） */
public class AfterSaleRuleVO {

    private Integer id;
    private String ruleName;
    private String ruleType;
    private Integer categoryId;
    private String applyScene;
    private String conditionText;
    private String processText;
    private String timeLimit;
    private String contactChannel;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public String getApplyScene() {
        return applyScene;
    }

    public void setApplyScene(String applyScene) {
        this.applyScene = applyScene;
    }

    public String getConditionText() {
        return conditionText;
    }

    public void setConditionText(String conditionText) {
        this.conditionText = conditionText;
    }

    public String getProcessText() {
        return processText;
    }

    public void setProcessText(String processText) {
        this.processText = processText;
    }

    public String getTimeLimit() {
        return timeLimit;
    }

    public void setTimeLimit(String timeLimit) {
        this.timeLimit = timeLimit;
    }

    public String getContactChannel() {
        return contactChannel;
    }

    public void setContactChannel(String contactChannel) {
        this.contactChannel = contactChannel;
    }
}
