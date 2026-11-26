package com.example.deploy.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一台目标服务器的连接配置（账号密码 / 私钥 / su 提权等）。
 *
 * <p>发布相关的配置（发布目录、jar 名称、启动参数等）已抽离到 {@link DeployTarget}：
 * 一个服务器可挂多个发布配置，分别发布不同的 jar 包。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig {

    /** 主键，新增时自动生成 */
    private String id;

    /** 名称，如 "测试环境-订单服务" */
    private String name;

    /** 服务器 IP / 域名 */
    private String host;

    /** SSH 端口 */
    private Integer port = 22;

    /** 登录账号 */
    private String username;

    /**
     * 登录密码。持久化到 servers.json 时自动加密（ENC: 前缀），
     * 在 application.yml 中可直接写明文。
     */
    private String password;

    /**
     * 是否开启动态码（MFA）：开启后首次连接该服务器前，前端会弹窗要求输入动态码，
     * 动态码拼接到密码末尾用于 SSH 认证；动态码本身不落盘，只在本服务内存中临时保存。
     */
    private Boolean dynamicCodeEnabled = Boolean.FALSE;

    /** 认证方式：PASSWORD（默认） / PRIVATE_KEY */
    private String authType = "PASSWORD";

    /** 私钥文件路径（authType=PRIVATE_KEY 时使用，路径为运行本工具的机器上的路径） */
    private String privateKeyPath;

    /** 私钥口令，可为空 */
    private String passphrase;

    /** 备注 */
    private String remark;

    /** 所属文件夹（用于左侧服务器分组管理）；为空表示「未分组」 */
    private String group;

    /**
     * 是否登录后切换为其他用户（如 root）再执行所有操作。
     * 很多生产机只能用普通账号 SSH 登录，需要 su 切 root 才有 /opt 等目录的写权限。
     */
    private Boolean suEnabled = Boolean.FALSE;

    /** su 切换的目标用户，默认 root */
    private String suUser = "root";

    /**
     * 切换目标用户所需的密码（即 su 要输入的那个密码）。
     * 持久化时同样加密（ENC: 前缀），返回前端时剥离。
     */
    private String suPassword;

    /** 仅用于前端展示：是否已配置 su 密码 */
    private Boolean suPasswordConfigured;

    /**
     * 远程中转根目录（上传先落到此目录再 cp 到目标）：每台服务器可独立配置。
     * 为空时回退到全局 deploy.staging-dir（默认 /tmp）。
     * 必须与登录账号对该目录有写权限，否则上传中转失败。
     */
    private String stagingDir;

    private String createTime;

    private String updateTime;

    /** 仅用于前端展示：是否已配置密码（返回前端时不带真实密码） */
    private Boolean passwordConfigured;

    public int effectivePort() {
        return port == null || port <= 0 ? 22 : port.intValue();
    }

    public boolean usePrivateKey() {
        return "PRIVATE_KEY".equalsIgnoreCase(authType);
    }

    public boolean isSuEnabled() {
        return Boolean.TRUE.equals(suEnabled);
    }

    /** 是否开启登录动态码（MFA） */
    public boolean isDynamicCodeEnabled() {
        return Boolean.TRUE.equals(dynamicCodeEnabled);
    }

    /** su 目标用户，留空默认 root */
    public String effectiveSuUser() {
        return isNotBlank(suUser) ? suUser.trim() : "root";
    }

    /** 返回给前端的安全副本：去掉密码与私钥口令 */
    public ServerConfig toSafe() {
        ServerConfig c = copy();
        c.setPasswordConfigured(Boolean.valueOf(isNotBlank(password)));
        c.setPassword(null);
        c.setPassphrase(null);
        c.setSuPasswordConfigured(Boolean.valueOf(isNotBlank(suPassword)));
        c.setSuPassword(null);
        return c;
    }

    public ServerConfig copy() {
        ServerConfig c = new ServerConfig();
        c.id = this.id;
        c.name = this.name;
        c.host = this.host;
        c.port = this.port;
        c.username = this.username;
        c.password = this.password;
        c.dynamicCodeEnabled = this.dynamicCodeEnabled;
        c.authType = this.authType;
        c.privateKeyPath = this.privateKeyPath;
        c.passphrase = this.passphrase;
        c.remark = this.remark;
        c.group = this.group;
        c.suEnabled = this.suEnabled;
        c.suUser = this.suUser;
        c.suPassword = this.suPassword;
        c.suPasswordConfigured = this.suPasswordConfigured;
        c.stagingDir = this.stagingDir;
        c.createTime = this.createTime;
        c.updateTime = this.updateTime;
        c.passwordConfigured = this.passwordConfigured;
        return c;
    }

    private static boolean isNotBlank(String s) {
        return s != null && s.trim().length() > 0;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public Boolean getDynamicCodeEnabled() {
        return dynamicCodeEnabled;
    }

    public void setDynamicCodeEnabled(Boolean dynamicCodeEnabled) {
        this.dynamicCodeEnabled = dynamicCodeEnabled;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPassphrase() {
        return passphrase;
    }

    public void setPassphrase(String passphrase) {
        this.passphrase = passphrase;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public Boolean getSuEnabled() {
        return suEnabled;
    }

    public void setSuEnabled(Boolean suEnabled) {
        this.suEnabled = suEnabled;
    }

    public String getSuUser() {
        return suUser;
    }

    public void setSuUser(String suUser) {
        this.suUser = suUser;
    }

    public String getSuPassword() {
        return suPassword;
    }

    public void setSuPassword(String suPassword) {
        this.suPassword = suPassword;
    }

    public Boolean getSuPasswordConfigured() {
        return suPasswordConfigured;
    }

    public void setSuPasswordConfigured(Boolean suPasswordConfigured) {
        this.suPasswordConfigured = suPasswordConfigured;
    }

    public String getStagingDir() {
        return stagingDir;
    }

    public void setStagingDir(String stagingDir) {
        this.stagingDir = stagingDir;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public Boolean getPasswordConfigured() {
        return passwordConfigured;
    }

    public void setPasswordConfigured(Boolean passwordConfigured) {
        this.passwordConfigured = passwordConfigured;
    }
}
