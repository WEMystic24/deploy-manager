package com.example.deploy.model;

/**
 * 远程 Java 应用运行状态。
 */
public class AppStatus {

    private String serverId;
    private String serverName;
    /** 发布配置 id 与名称（一台服务器可有多个发布目标） */
    private String targetId;
    private String targetName;
    /** 是否连通 SSH */
    private boolean connected;
    /** 进程是否存活 */
    private boolean running;
    private String pid;
    /** 命中的全部进程 PID（空格分隔）：多 jar 时可能不止一个，pid 是其中的主进程 */
    private String pids;
    /** 进程启动时间 */
    private String startTime;
    /** 已运行时长 */
    private String uptime;
    /** 占用内存 */
    private String memory;
    /** 远程 jar 文件信息（大小 / 修改时间） */
    private String jarInfo;
    private String jarPath;
    /** info 日志文件（默认启动命令的输出） */
    private String logFile;
    /** error 日志文件（通常由应用日志框架写入） */
    private String errorLogFile;
    private String message;

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

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public String getPid() {
        return pid;
    }

    public void setPid(String pid) {
        this.pid = pid;
    }

    public String getPids() {
        return pids;
    }

    public void setPids(String pids) {
        this.pids = pids;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getUptime() {
        return uptime;
    }

    public void setUptime(String uptime) {
        this.uptime = uptime;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getJarInfo() {
        return jarInfo;
    }

    public void setJarInfo(String jarInfo) {
        this.jarInfo = jarInfo;
    }

    public String getJarPath() {
        return jarPath;
    }

    public void setJarPath(String jarPath) {
        this.jarPath = jarPath;
    }

    public String getLogFile() {
        return logFile;
    }

    public void setLogFile(String logFile) {
        this.logFile = logFile;
    }

    public String getErrorLogFile() {
        return errorLogFile;
    }

    public void setErrorLogFile(String errorLogFile) {
        this.errorLogFile = errorLogFile;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
