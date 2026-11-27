package com.example.deploy.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一台服务器上的一个发布配置（发布目标）。
 *
 * <p>与服务器的「连接信息」解耦：一个服务器（ServerConfig）可挂多个发布配置，
 * 各自拥有独立的发布目录、jar 名称与启动参数，可分别发布不同的 jar 包。</p>
 */
public class DeployTarget {

    /** 主键，新增时自动生成 */
    private String id;

    /** 所属服务器 id */
    private String serverId;

    /** 发布配置名称，如 "订单服务"、"网关服务" */
    private String name;

    /** jar 包发布到的远程目录，如 /opt/app/order（必填，后端起服目录） */
    private String remoteDir;

    /**
     * 配置文件（yml / properties 等）所在目录，选填；留空则为 {remoteDir}/conf。
     * 在「上传 conf 文件」时作为目标目录，发布流程本身不改动该目录内的文件。
     */
    private String confDir;

    /** 远程 jar 文件名，如 order-service.jar */
    private String jarName;

    /**
     * 主 jar 包名称（可选）：一个发布目录里有多个 jar（自定义部署）时，
     * 状态查询 / 看板 PID / 启动后存活复核只认这个 jar，避免把同目录另一个 jar 的进程当成主服务。
     * 留空则「任一 jar 命中即算运行中」，PID 取定位到的第一个。
     */
    private String primaryJar;

    /** java 命令，默认 java，也可写绝对路径 /usr/local/jdk1.8/bin/java */
    private String javaCmd = "java";

    /** JVM 参数，如 -Xms512m -Xmx1024m */
    private String jvmOpts;

    /** 应用启动参数，如 --spring.profiles.active=test --server.port=8080 */
    private String appArgs;

    /**
     * info 日志文件，留空则为 {remoteDir}/logs/{jarName}.log。
     * 默认启动命令（nohup … >> 日志 2>&1）的输出写到该文件；运行日志页「info」页签读的也是它。
     */
    private String infoLogFile;

    /**
     * error 日志文件，留空则为 {remoteDir}/logs/{jarName}-error.log。
     * 通常由应用日志框架（logback 等）单独写入；运行日志页「error」页签读的是它。
     */
    private String errorLogFile;

    /** 旧字段（历史配置里的 logFile），仅用于读取旧 JSON，等价于 infoLogFile，不再写出（无 getter 即不参与序列化） */
    private String logFile;

    /** pid 文件，留空则为 {remoteDir}/{jarName}.pid */
    private String pidFile;

    /** 备份目录，留空则为 {remoteDir}/backup */
    private String backupDir;

    /** 启动前执行的自定义命令，可为空 */
    private String preStartCmd;

    /** 启动后执行的自定义命令，可为空 */
    private String postStartCmd;

    /** 本机 jar 路径：编辑发布配置时填写，用于「本机路径发布」（保存后可直接用本机已有的 jar 推送） */
    private String localPath;

    /** 本机路径发布后是否自动重启，默认 true */
    private boolean restartLocal = true;

    /**
     * 配置类型：backend（后端 Java 应用，沿用 jar/java/jvm/appArgs 等完整配置）
     * 或 frontend（前端静态资源，只需本机发布路径 + 发布后执行命令）。
     */
    private String type = "backend";

    /** 前端类型：本机发布路径（本地静态资源目录或打包产物路径） */
    private String frontendPath;

    /**
     * 前端类型：服务器上的部署子文件夹名由「本机发布路径 frontendPath」的末级目录名自动推导
     * （如 frontendPath=/opt/build/web 则部署到 remoteDir/web），不再需要手填 folderName 字段。
     */


    /** 前端类型：发布后执行的命令（如复制到 nginx 目录、构建等） */
    private String frontendPostCmd;

    /** 前端类型：发布前是否把本机发布路径打成 zip 再上传（否则直接整目录上传） */
    private boolean zip = false;

    /**
     * 前端类型：发布时对目标发布目录的处理方式。
     * false=覆盖更新（默认，同名文件覆盖，旧目录中已删除的文件会残留）；
     * true=清空后更新（先清空目标目录内容再上传/解压，得到干净目录，不影响备份与回滚）。
     */
    private boolean clearBeforeDeploy = false;

    /**
     * 历史字段：以前是「发布前备份」开关。
     * 现在发布前<b>始终</b>备份旧 jar / conf 文件（保留最近 keep-backup-count 个版本），
     * 该字段只为兼容旧配置文件保留，不再参与任何判断。
     */
    @Deprecated
    private boolean backupEnabled = false;

    /**
     * 后端部署模式（仅后端类型有效）：
     * <ul>
     *   <li>default：默认部署，单个 jar，沿用 jar 名称 / java 命令 / JVM 参数自动生成的
     *       {@code nohup java -jar <jar名> >> <日志文件> 2>&1 &} 启动命令（与历史行为完全一致）；
     *       填了「自定义启动命令」时以自定义命令为准；</li>
     *   <li>custom：自定义部署，支持一次选择多个 jar 包统一上传更新。</li>
     * </ul>
     * 两种方式都支持「自定义启动命令」，见 {@link #startCommand}。
     */
    private String deployMode = "default";

    /**
     * 自定义启动命令（默认部署 / 自定义部署都可用）。填写后<strong>完全覆盖</strong>默认启动命令：
     * 不再自动 cd 到 jar 包目录、不再校验 jar 是否存在，按命令原文执行（不填仍走默认命令）。
     * 支持占位符：{remoteDir} {confDir} {jarName} {jarPath} {jars} {logFile} {pidFile} {javaCmd} {jvmOpts} {appArgs} {backupDir}，
     * 执行前会替换为实际值；命令本身需后台运行（如以 nohup ... & 或 & 结尾）。
     */
    private String startCommand;

    /**
     * 自定义部署：需要统一上传更新的 jar 包文件名列表（可多个）。
     * 上传时保持原文件名落到发布目录，不重命名为 jarName。
     */
    private List<String> jarNames = new ArrayList<String>();

    private String createTime;

    private String updateTime;

    /** 最近一次成功发布（DEPLOY）的时间，格式 yyyy-MM-dd HH:mm:ss；未发布过为 null */
    private String lastDeployTime;

    public String jarPath() {
        return trimSlash(remoteDir) + "/" + jarName;
    }

    /** 是否为「自定义部署」模式（仅后端类型有意义） */
    @JsonIgnore
    public boolean isCustomDeploy() {
        return "custom".equalsIgnoreCase(deployMode == null ? "" : deployMode.trim());
    }

    /**
     * 参与上传 / 启停 / 备份 / 回滚的 jar 文件名列表：
     * 自定义部署取 jarNames（为空时回退 jarName），默认部署仅取 jarName。
     */
    public List<String> effectiveJarNames() {
        List<String> list = new ArrayList<String>();
        if (isCustomDeploy()) {
            if (jarNames != null) {
                for (String n : jarNames) {
                    if (isNotBlank(n) && !list.contains(n.trim())) {
                        list.add(n.trim());
                    }
                }
            }
            if (list.isEmpty() && isNotBlank(jarName)) {
                list.add(jarName.trim());
            }
        } else if (isNotBlank(jarName)) {
            list.add(jarName.trim());
        }
        return list;
    }

    /** 发布目录下指定 jar 的远程完整路径 */
    public String jarPath(String name) {
        return trimSlash(remoteDir) + "/" + (name == null ? "" : name.trim());
    }

    /**
     * 状态查询 / 启动复核使用的 jar 名称列表：配了「主 jar 包」就只认它（传完整路径时只取末级文件名），
     * 否则退化为全部 jar（任一命中即算运行中）。
     * <p>停止 / 备份 / 回滚始终按全部 jar 处理，见 {@link #effectiveJarNames()}。
     */
    public List<String> monitorJarNames() {
        if (isNotBlank(primaryJar)) {
            String p = primaryJar.trim();
            int slash = p.lastIndexOf('/');
            if (slash >= 0) {
                p = p.substring(slash + 1).trim();
            }
            if (!p.isEmpty()) {
                List<String> one = new ArrayList<String>();
                one.add(p);
                return one;
            }
        }
        return effectiveJarNames();
    }

    /**
     * 自定义启动命令的最终形态（占位符已替换）；命令为空时返回 null，表示沿用默认命令。
     * 默认部署与自定义部署共用这一个配置项。
     */
    public String resolvedStartCommand() {
        if (isBlank(startCommand)) {
            return null;
        }
        StringBuilder jars = new StringBuilder();
        for (String n : effectiveJarNames()) {
            if (jars.length() > 0) {
                jars.append(' ');
            }
            jars.append(jarPath(n));
        }
        String cmd = startCommand;
        cmd = cmd.replace("{remoteDir}", trimSlash(remoteDir));
        cmd = cmd.replace("{jarPath}", isNotBlank(jarName) ? jarPath() : jars.toString());
        cmd = cmd.replace("{jarName}", trimToEmpty(jarName));
        cmd = cmd.replace("{jars}", jars.toString());
        cmd = cmd.replace("{logFile}", effectiveInfoLogFile());
        cmd = cmd.replace("{infoLogFile}", effectiveInfoLogFile());
        cmd = cmd.replace("{errorLogFile}", effectiveErrorLogFile());
        cmd = cmd.replace("{pidFile}", effectivePidFile());
        cmd = cmd.replace("{javaCmd}", effectiveJavaCmd());
        cmd = cmd.replace("{jvmOpts}", trimToEmpty(jvmOpts));
        cmd = cmd.replace("{appArgs}", trimToEmpty(appArgs));
        cmd = cmd.replace("{backupDir}", effectiveBackupDir());
        cmd = cmd.replace("{confDir}", effectiveConfDir());
        return cmd;
    }

    /** info 日志文件：填写则用填写的绝对路径，留空则 {remoteDir}/logs/{jarName}.log */
    public String effectiveInfoLogFile() {
        if (isNotBlank(infoLogFile)) {
            return infoLogFile.trim();
        }
        return trimSlash(remoteDir) + "/logs/" + stripExt(jarName) + ".log";
    }

    /** error 日志文件：填写则用填写的绝对路径，留空则 {remoteDir}/logs/{jarName}-error.log */
    public String effectiveErrorLogFile() {
        if (isNotBlank(errorLogFile)) {
            return errorLogFile.trim();
        }
        return trimSlash(remoteDir) + "/logs/" + stripExt(jarName) + "-error.log";
    }

    /**
     * 按日志类型取日志文件路径：error → error 日志，其它（含空）→ info 日志。
     */
    public String logFileOf(String type) {
        return "error".equalsIgnoreCase(type == null ? "" : type.trim()) ? effectiveErrorLogFile() : effectiveInfoLogFile();
    }

    /** 兼容旧调用：未区分 info / error 时一律视为 info 日志 */
    public String effectiveLogFile() {
        return effectiveInfoLogFile();
    }

    /**
     * 是否需要日志文件（及其所在目录）：
     * <ul>
     *   <li>未填写自定义启动命令：默认命令固定 {@code >> info 日志 2>&1}，需要创建 info 日志目录；</li>
     *   <li>填写了自定义启动命令：只有命令里真的引用了 {@code {logFile}} / {@code {infoLogFile}} / {@code {errorLogFile}}
     *       占位符或对应日志文件路径时才需要。命令自行决定日志去处（如交给 systemd、输出到其它位置或不记日志）时，
     *       不再创建多余的 logs 目录。</li>
     * </ul>
     */
    @JsonIgnore
    public boolean needsLogFile() {
        if (isBlank(startCommand)) {
            return true;
        }
        String cmd = startCommand;
        if (cmd.contains("{logFile}") || cmd.contains("{infoLogFile}") || cmd.contains("{errorLogFile}")) {
            return true;
        }
        String info = effectiveInfoLogFile();
        String error = effectiveErrorLogFile();
        return (isNotBlank(info) && cmd.contains(info)) || (isNotBlank(error) && cmd.contains(error));
    }

    /** 是否显式配置了 info 日志路径（例如日志框架写入的固定路径，与启动命令重定向无关） */
    @JsonIgnore
    public boolean hasConfiguredInfoLog() {
        return isNotBlank(infoLogFile);
    }

    /** 是否显式配置了 error 日志路径（用于决定是否为其创建目录） */
    @JsonIgnore
    public boolean hasConfiguredErrorLog() {
        return isNotBlank(errorLogFile);
    }

    public String effectivePidFile() {
        if (isNotBlank(pidFile)) {
            return pidFile.trim();
        }
        return trimSlash(remoteDir) + "/" + stripExt(jarName) + ".pid";
    }

    /**
     * 备份目录（实际使用的位置）：
     * <ul>
     *   <li>填写了 backupDir：用它（目录不存在/不可写时由脚本回退到发布目录）；</li>
     *   <li>后端未填写：jar 包目录本身（备份与原 jar 同目录、文件名加时间戳后缀）；</li>
     *   <li>前端未填写：{@code 发布目录/backup}（避免备份包落在站点根目录被外部下载）。</li>
     * </ul>
     */
    public String effectiveBackupDir() {
        if (isNotBlank(backupDir)) {
            return trimSlash(backupDir);
        }
        String root = trimSlash(remoteDir);
        return "frontend".equals(type) ? root + "/backup" : root;
    }

    /** 是否显式配置了备份目录（决定 jar / conf 备份是集中存放还是各自就地备份） */
    @JsonIgnore
    public boolean hasConfiguredBackupDir() {
        return isNotBlank(backupDir);
    }

    /** 配置文件目录：填写则用填写的绝对路径，留空则用 {remoteDir}/conf */
    public String effectiveConfDir() {
        if (isNotBlank(confDir)) {
            return trimSlash(confDir);
        }
        return trimSlash(remoteDir) + "/conf";
    }

    public String effectiveJavaCmd() {
        return isNotBlank(javaCmd) ? javaCmd.trim() : "java";
    }

    /** 前端类型有效发布目录：remoteDir / 本机发布路径末级目录名（自动推导，无需手填文件夹名） */
    public String effectiveFrontendDir() {
        String base = trimSlash(remoteDir);
        String fn = deployFolderName();
        return isNotBlank(fn) ? base + "/" + trimSlash(fn) : base;
    }

    /**
     * 根据「本机发布路径」推导服务器部署子文件夹名：取路径末级目录名（如 /opt/build/web → web）。
     * 若路径为空或末级为根，返回 null。
     */
    public String deployFolderName() {
        if (isBlank(frontendPath)) {
            return null;
        }
        String p = frontendPath.trim();
        while (p.endsWith("/") || p.endsWith("\\")) {
            p = p.substring(0, p.length() - 1);
        }
        int k = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String seg = k >= 0 ? p.substring(k + 1) : p;
        return seg.isEmpty() ? null : seg;
    }

    public DeployTarget copy() {
        DeployTarget t = new DeployTarget();
        t.id = this.id;
        t.serverId = this.serverId;
        t.name = this.name;
        t.remoteDir = this.remoteDir;
        t.confDir = this.confDir;
        t.jarName = this.jarName;
        t.primaryJar = this.primaryJar;
        t.javaCmd = this.javaCmd;
        t.jvmOpts = this.jvmOpts;
        t.appArgs = this.appArgs;
        t.infoLogFile = this.infoLogFile;
        t.errorLogFile = this.errorLogFile;
        t.logFile = this.logFile;
        t.pidFile = this.pidFile;
        t.backupDir = this.backupDir;
        t.preStartCmd = this.preStartCmd;
        t.postStartCmd = this.postStartCmd;
        t.localPath = this.localPath;
        t.restartLocal = this.restartLocal;
        t.type = this.type;
        t.frontendPath = this.frontendPath;
        t.frontendPostCmd = this.frontendPostCmd;
        t.zip = this.zip;
        t.clearBeforeDeploy = this.clearBeforeDeploy;
        t.backupEnabled = this.backupEnabled;
        t.deployMode = this.deployMode;
        t.startCommand = this.startCommand;
        t.jarNames = this.jarNames == null ? new ArrayList<String>() : new ArrayList<String>(this.jarNames);
        t.createTime = this.createTime;
        t.updateTime = this.updateTime;
        t.lastDeployTime = this.lastDeployTime;
        return t;
    }

    private static boolean isNotBlank(String s) {
        return s != null && s.trim().length() > 0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String trimSlash(String dir) {
        if (dir == null) {
            return "";
        }
        String d = dir.trim();
        while (d.length() > 1 && d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }

    private static String stripExt(String jar) {
        if (jar == null) {
            return "app";
        }
        int i = jar.lastIndexOf('.');
        return i > 0 ? jar.substring(0, i) : jar;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemoteDir() {
        return remoteDir;
    }

    public void setRemoteDir(String remoteDir) {
        this.remoteDir = remoteDir;
    }

    public String getConfDir() {
        return confDir;
    }

    public void setConfDir(String confDir) {
        this.confDir = confDir;
    }

    public String getJarName() {
        return jarName;
    }

    public void setJarName(String jarName) {
        this.jarName = jarName;
    }

    public String getPrimaryJar() {
        return primaryJar;
    }

    public void setPrimaryJar(String primaryJar) {
        this.primaryJar = primaryJar;
    }

    public String getJavaCmd() {
        return javaCmd;
    }

    public void setJavaCmd(String javaCmd) {
        this.javaCmd = javaCmd;
    }

    public String getJvmOpts() {
        return jvmOpts;
    }

    public void setJvmOpts(String jvmOpts) {
        this.jvmOpts = jvmOpts;
    }

    public String getAppArgs() {
        return appArgs;
    }

    public void setAppArgs(String appArgs) {
        this.appArgs = appArgs;
    }

    public String getInfoLogFile() {
        return infoLogFile;
    }

    public void setInfoLogFile(String infoLogFile) {
        this.infoLogFile = infoLogFile;
    }

    public String getErrorLogFile() {
        return errorLogFile;
    }

    public void setErrorLogFile(String errorLogFile) {
        this.errorLogFile = errorLogFile;
    }

    /**
     * 旧配置兼容：历史 JSON 里只有一个 logFile 字段，读入时视为 info 日志。
     * 不提供 getter，避免新配置里再写出冗余的 logFile。
     */
    @JsonProperty("logFile")
    public void setLogFile(String logFile) {
        this.logFile = logFile;
        if (isBlank(infoLogFile)) {
            this.infoLogFile = logFile;
        }
    }

    public String getPidFile() {
        return pidFile;
    }

    public void setPidFile(String pidFile) {
        this.pidFile = pidFile;
    }

    public String getBackupDir() {
        return backupDir;
    }

    public void setBackupDir(String backupDir) {
        this.backupDir = backupDir;
    }

    public String getPreStartCmd() {
        return preStartCmd;
    }

    public void setPreStartCmd(String preStartCmd) {
        this.preStartCmd = preStartCmd;
    }

    public String getPostStartCmd() {
        return postStartCmd;
    }

    public void setPostStartCmd(String postStartCmd) {
        this.postStartCmd = postStartCmd;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public boolean isRestartLocal() {
        return restartLocal;
    }

    public void setRestartLocal(boolean restartLocal) {
        this.restartLocal = restartLocal;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFrontendPath() {
        return frontendPath;
    }

    public void setFrontendPath(String frontendPath) {
        this.frontendPath = frontendPath;
    }

    public String getFrontendPostCmd() {
        return frontendPostCmd;
    }

    public void setFrontendPostCmd(String frontendPostCmd) {
        this.frontendPostCmd = frontendPostCmd;
    }

    public boolean isZip() {
        return zip;
    }

    public void setZip(boolean zip) {
        this.zip = zip;
    }

    public boolean isClearBeforeDeploy() {
        return clearBeforeDeploy;
    }

    public void setClearBeforeDeploy(boolean clearBeforeDeploy) {
        this.clearBeforeDeploy = clearBeforeDeploy;
    }

    /** @deprecated 发布前始终备份，不再读取该开关 */
    @Deprecated
    public boolean isBackupEnabled() {
        return backupEnabled;
    }

    public void setBackupEnabled(boolean backupEnabled) {
        this.backupEnabled = backupEnabled;
    }

    public String getDeployMode() {
        return deployMode;
    }

    public void setDeployMode(String deployMode) {
        this.deployMode = deployMode;
    }

    public String getStartCommand() {
        return startCommand;
    }

    public void setStartCommand(String startCommand) {
        this.startCommand = startCommand;
    }

    public List<String> getJarNames() {
        return jarNames;
    }

    public void setJarNames(List<String> jarNames) {
        this.jarNames = jarNames;
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

    public String getLastDeployTime() {
        return lastDeployTime;
    }

    public void setLastDeployTime(String lastDeployTime) {
        this.lastDeployTime = lastDeployTime;
    }
}
