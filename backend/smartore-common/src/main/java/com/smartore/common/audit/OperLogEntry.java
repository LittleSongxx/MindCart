package com.smartore.common.audit;

/**
 * 一条审计记录（跨服务的公共字段集）。
 * 各服务把它落到自己的 oper_log 表；表结构差异（如额外的业务列）由服务侧自行扩展。
 */
public class OperLogEntry {

    /** 操作人（网关注入的当前用户），未登录为 null */
    private Integer userId;
    /** 操作人角色快照：角色会变，留痕要记录当时的身份 */
    private String role;
    private String module;
    private String action;
    private String requestUri;
    private String httpMethod;
    private String clientIp;
    /** 入参摘要（截断），敏感接口由 @OperationLog(recordArgs=false) 关掉 */
    private String args;
    /** 成功/失败 */
    private String result;
    /** 失败原因（截断） */
    private String errorMsg;
    /** 耗时毫秒 */
    private Long costMs;

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getRequestUri() {
        return requestUri;
    }

    public void setRequestUri(String requestUri) {
        this.requestUri = requestUri;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getArgs() {
        return args;
    }

    public void setArgs(String args) {
        this.args = args;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Long getCostMs() {
        return costMs;
    }

    public void setCostMs(Long costMs) {
        this.costMs = costMs;
    }
}
