package com.example.deploy.model;

/**
 * 操作记录（谁在什么时候对哪台服务器做了什么）。
 */
public class OperationLog {

    private String time;
    private String serverId;
    private String serverName;
    /** DEPLOY / START / STOP / RESTART / ROLLBACK / TEST */
    private String action;
    private boolean success;
    private long costMs;
    private String message;

    public OperationLog() {
    }

    public OperationLog(String time, String serverId, String serverName, String action,
                        boolean success, long costMs, String message) {
        this.time = time;
        this.serverId = serverId;
        this.serverName = serverName;
        this.action = action;
        this.success = success;
        this.costMs = costMs;
        this.message = message;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
