package com.example.deploy.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.deploy.model.ServerConfig;

/**
 * 全局配置，对应 application.yml 中的 deploy.* 节点。
 */
@ConfigurationProperties(prefix = "deploy")
public class DeployProperties {

    /** 服务器配置持久化文件（默认绝对路径，避免从不同目录启动 jar 时配置“丢失”） */
    private String dataFile = userHome(".deploy-manager/servers.json");

    /** 发布配置持久化文件（一台服务器可配置多个发布目标） */
    private String targetFile = userHome(".deploy-manager/targets.json");

    /** 上传的 jar 临时落地目录 */
    private String uploadDir = userHome(".deploy-manager/uploads");

    /** 操作记录文件 */
    private String operationLogFile = userHome(".deploy-manager/operation.log");

    /**
     * 远程中转根目录：上传先落到此目录下的子目录（deploy-staging / jdk-install / nacos-install），
     * 再由 exec（su 提权时为 root）cp 到目标路径。默认 /tmp（登录账号必可写）。
     * 若 /tmp 空间不足或合规要求不能用 /tmp，可在 yml 中改为登录账号可写的其他目录，如 /data/tmp。
     */
    private String stagingDir = "/tmp";

    /** 服务器文件夹（分组）列表持久化文件 */
    private String groupFile = userHome(".deploy-manager/server-groups.json");

    /** 登录验证开关持久化文件（仅保存 enabled，运行时切换后落盘，重启保持） */
    private String authFile = userHome(".deploy-manager/auth.json");

    /** 基于当前用户主目录拼出绝对路径 */
    private static String userHome(String sub) {
        return System.getProperty("user.home") + "/" + sub;
    }

    /** 密码加密密钥，生产环境请务必修改 */
    private String secretKey = "DeployManager@2026";

    /** SSH 连接超时（秒） */
    private int connectTimeoutSeconds = 15;

    /** 远程命令执行超时（秒） */
    private int execTimeoutSeconds = 180;

    /** 每台服务器保留的历史备份数量，超出自动清理 */
    private int keepBackupCount = 10;

    /** 停止进程时等待优雅退出的秒数，超时 kill -9 */
    private int stopWaitSeconds = 20;

    /** 启动后等待并检查进程存活的秒数 */
    private int startWaitSeconds = 8;

    /** 访问认证（默认开启：访问 index.html 等页面必须先登录） */
    private Auth auth = new Auth();

    /** yml 中预置的服务器，首次启动时导入到 dataFile */
    private List<ServerConfig> servers = new ArrayList<ServerConfig>();

    public static class Auth {
        private boolean enabled = true;
        private String username = "admin";
        private String password = "Admin123";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
    }

    public String getDataFile() {
        return dataFile;
    }

    public void setDataFile(String dataFile) {
        this.dataFile = dataFile;
    }

    public String getTargetFile() {
        return targetFile;
    }

    public void setTargetFile(String targetFile) {
        this.targetFile = targetFile;
    }

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public String getOperationLogFile() {
        return operationLogFile;
    }

    public void setOperationLogFile(String operationLogFile) {
        this.operationLogFile = operationLogFile;
    }

    public String getStagingDir() {
        return stagingDir;
    }

    public void setStagingDir(String stagingDir) {
        this.stagingDir = stagingDir;
    }

    public String getGroupFile() {
        return groupFile;
    }

    public void setGroupFile(String groupFile) {
        this.groupFile = groupFile;
    }

    public String getAuthFile() {
        return authFile;
    }

    public void setAuthFile(String authFile) {
        this.authFile = authFile;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getExecTimeoutSeconds() {
        return execTimeoutSeconds;
    }

    public void setExecTimeoutSeconds(int execTimeoutSeconds) {
        this.execTimeoutSeconds = execTimeoutSeconds;
    }

    public int getKeepBackupCount() {
        return keepBackupCount;
    }

    public void setKeepBackupCount(int keepBackupCount) {
        this.keepBackupCount = keepBackupCount;
    }

    public int getStopWaitSeconds() {
        return stopWaitSeconds;
    }

    public void setStopWaitSeconds(int stopWaitSeconds) {
        this.stopWaitSeconds = stopWaitSeconds;
    }

    public int getStartWaitSeconds() {
        return startWaitSeconds;
    }

    public void setStartWaitSeconds(int startWaitSeconds) {
        this.startWaitSeconds = startWaitSeconds;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public List<ServerConfig> getServers() {
        return servers;
    }

    public void setServers(List<ServerConfig> servers) {
        this.servers = servers;
    }
}
