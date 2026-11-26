package com.example.deploy.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.deploy.config.DeployProperties;
import com.example.deploy.model.AppStatus;
import com.example.deploy.model.CommandResult;
import com.example.deploy.model.DeployResult;
import com.example.deploy.model.DeployTarget;
import com.example.deploy.model.DeployTask;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.model.StepStatus;
import com.example.deploy.ssh.SshService;
import com.example.deploy.ssh.SshSession;
import com.example.deploy.store.DeployTargetStore;
import com.example.deploy.store.ServerConfigStore;
import com.example.deploy.util.ShellUtil;
import com.jcraft.jsch.SftpProgressMonitor;

/**
 * 发布 / 启停 / 状态查询的核心逻辑。
 *
 * <p>操作维度是「发布配置」（DeployTarget）：一个服务器可挂多个发布配置，
 * 各自拥有独立的发布目录、jar 名称与启动参数。接口入参均为 targetId。</p>
 *
 * <p>发布、启动、停止、重启、回滚均改为<b>异步执行</b>：方法立即返回一个 {@link DeployTask}（含 taskId
 * 与预声明的步骤列表），真正的操作在后台线程中进行，每进入 / 完成一个步骤都会把进度推送给前端，
 * 上传阶段还会实时上报字节进度。前端用 taskId 通过 SSE 订阅实时进度。</p>
 */
@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    private final DeployProperties properties;
    private final ServerConfigStore store;
    private final DeployTargetStore targetStore;
    private final SshService sshService;
    private final OperationLogService operationLogService;

    /** 后台执行线程池 */
    private final ExecutorService executor = Executors.newCachedThreadPool();
    /** taskId -> 任务 */
    private final ConcurrentHashMap<String, DeployTask> taskMap = new ConcurrentHashMap<String, DeployTask>();
    /** targetId -> 最近一次任务 id（供前端重连 / 查看） */
    private final ConcurrentHashMap<String, String> lastTaskByTarget = new ConcurrentHashMap<String, String>();

    public DeployService(DeployProperties properties, ServerConfigStore store, DeployTargetStore targetStore,
                         SshService sshService, OperationLogService operationLogService) {
        this.properties = properties;
        this.store = store;
        this.targetStore = targetStore;
        this.sshService = sshService;
        this.operationLogService = operationLogService;
    }

    // ---------------------------------------------------------------- 任务查询

    public DeployTask getTask(String taskId) {
        return taskMap.get(taskId);
    }

    public String lastTaskId(String targetId) {
        return lastTaskByTarget.get(targetId);
    }

    // ---------------------------------------------------------------- 发布入口

    /** 页面上传 jar 后发布（异步，返回任务） */
    public DeployTask deployByUploadAsync(String targetId, MultipartFile file, boolean restart) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的 jar 包");
        }
        String originalName = file.getOriginalFilename() == null ? "app.jar" : file.getOriginalFilename();
        if (!originalName.toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("只允许上传 .jar 文件");
        }
        ServerConfig config = resolveConfig(targetId);
        DeployTarget target = resolveTarget(targetId);
        File local = target.isCustomDeploy()
                ? saveUploadFileKeepName(config, target, file, originalName)
                : saveUploadFile(config, target, file, originalName);
        DeployTask task = createTask(config, target, "DEPLOY");
        declareBackendSteps(task, target, restart);
        final ServerConfig cfg = config;
        LinkedHashMap<String, File> jars = new LinkedHashMap<String, File>();
        jars.put(remoteJarName(target, local, originalName), local);
        executor.submit(() -> doDeploy(task, cfg, target, jars, restart, "上传文件 " + originalName));
        return task;
    }

    /**
     * 一次上传多个 jar 包并统一发布（自定义部署专用）：所有 jar 先落到本服务，
     * 再在一个任务内依次传输到目标服务器（保持原文件名），最后只停止 / 启动一次。
     */
    public DeployTask deployByUploadsAsync(String targetId, List<MultipartFile> files, boolean restart) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的 jar 包");
        }
        DeployTarget probe = resolveTarget(targetId);
        if (files.size() > 1 && !probe.isCustomDeploy()) {
            throw new IllegalArgumentException("默认部署只支持单个 jar 包，如需一次更新多个 jar 请切换为「自定义部署」");
        }
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                throw new IllegalArgumentException("存在空的 jar 文件，请重新选择");
            }
            String n = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
            if (!n.toLowerCase().endsWith(".jar")) {
                throw new IllegalArgumentException("只允许上传 .jar 文件：" + n);
            }
        }
        ServerConfig config = resolveConfig(targetId);
        DeployTarget target = resolveTarget(targetId);
        LinkedHashMap<String, File> jars = new LinkedHashMap<String, File>();
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            File local = target.isCustomDeploy()
                    ? saveUploadFileKeepName(config, target, f, name)
                    : saveUploadFile(config, target, f, name);
            jars.put(remoteJarName(target, local, name), local);
        }
        DeployTask task = createTask(config, target, "DEPLOY");
        declareBackendSteps(task, target, restart);
        final ServerConfig cfg = config;
        final String desc = jars.size() == 1
                ? "上传文件 " + jars.keySet().iterator().next()
                : "上传 " + jars.size() + " 个 jar 包";
        executor.submit(() -> doDeploy(task, cfg, target, jars, restart, desc));
        return task;
    }

    /**
     * 上传到目标服务器时使用的 jar 文件名：自定义部署保持原文件名（自定义启动命令按名引用），
     * 默认部署统一重命名为配置里的 jarName。
     */
    private static String remoteJarName(DeployTarget target, File local, String originalName) {
        if (target.isCustomDeploy()) {
            String n = originalName == null ? "" : new File(originalName).getName().trim();
            // 分片上传合并产物命名为 final-<原文件名>，这里还原原始文件名
            if (n.startsWith("final-")) {
                n = n.substring("final-".length());
            }
            return n.isEmpty() ? local.getName() : n;
        }
        return target.getJarName();
    }


    /** 直接使用本机已有的 jar 发布（例如 Maven 打好的 target/xxx.jar），异步返回任务 */
    public DeployTask deployByLocalPathAsync(String targetId, String localPath, boolean restart) {
        if (ShellUtil.isBlank(localPath)) {
            throw new IllegalArgumentException("请填写本机 jar 包路径");
        }
        File local = new File(localPath.trim());
        if (!local.exists() || !local.isFile()) {
            throw new IllegalArgumentException("本机 jar 包不存在: " + local.getAbsolutePath());
        }
        if (!local.getName().toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("只支持 .jar 文件");
        }
        ServerConfig config = resolveConfig(targetId);
        DeployTarget target = resolveTarget(targetId);
        DeployTask task = createTask(config, target, "DEPLOY");
        declareBackendSteps(task, target, restart);
        final ServerConfig cfg = config;
        LinkedHashMap<String, File> jars = new LinkedHashMap<String, File>();
        jars.put(remoteJarName(target, local, local.getName()), local);
        executor.submit(() -> doDeploy(task, cfg, target, jars, restart, "本机路径 " + local.getAbsolutePath()));
        return task;
    }

    /**
     * 上传 conf 文件（yml / properties 等）到发布配置的 conf 目录：同名覆盖，不影响运行中的进程。
     * 目录不存在会自动创建；先 SFTP 到中转目录再 cp，兼容开启了 su 的 root 目录。
     *
     * @return {confDir: 实际目录, files: 已上传文件名列表}
     */
    public Map<String, Object> uploadConfFiles(String targetId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的 conf 文件");
        }
        DeployTarget target = targetStore.get(targetId);
        if (target == null) {
            throw new IllegalArgumentException("发布配置不存在");
        }
        if ("frontend".equals(target.getType())) {
            throw new IllegalArgumentException("前端配置不支持上传 conf 文件");
        }
        if (ShellUtil.isBlank(target.getRemoteDir())) {
            throw new IllegalArgumentException("发布配置「" + target.getName()
                    + "」尚未填写 jar 包目录，请先编辑发布配置");
        }
        ServerConfig config = store.getForConnect(target.getServerId());
        String confDir = target.effectiveConfDir();
        List<String> saved = new ArrayList<String>();
        long t0 = System.currentTimeMillis();
        SshSession session = null;
        try {
            session = sshService.open(config);
            CommandResult mk = exec(session, config, "mkdir -p " + ShellUtil.quote(confDir));
            if (!mk.isSuccess()) {
                throw new IllegalStateException("创建 conf 目录失败：" + mk.getOutput());
            }
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) {
                    continue;
                }
                String raw = f.getOriginalFilename();
                String name = raw == null ? "" : new File(raw).getName().trim();
                if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                    throw new IllegalArgumentException("非法的 conf 文件名：" + raw);
                }
                File local = saveUploadFile(config, target, f, name);
                try {
                    // 只取文件名落到 conf 目录，不保留浏览器带来的子目录结构
                    session.uploadViaStaging(local, confDir + "/" + name, null);
                } finally {
                    // 本服务只是中转，上传完成后删除本地临时文件
                    if (local.exists() && !local.delete()) {
                        log.warn("[conf] 本地临时文件清理失败: {}", local.getAbsolutePath());
                    }
                }
                saved.add(name);
                log.info("[conf] 已上传 {} → {}/{}", name, confDir, name);
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("confDir", confDir);
            result.put("files", saved);
            operationLogService.record(config, "CONF_UPLOAD", true,
                    System.currentTimeMillis() - t0,
                    "上传 conf 文件 " + saved.size() + " 个 → " + confDir + "：" + String.join("、", saved));
            return result;
        } catch (RuntimeException e) {
            operationLogService.record(config, "CONF_UPLOAD", false,
                    System.currentTimeMillis() - t0, "上传 conf 文件失败：" + e.getMessage());
            throw e;
        } finally {
            closeQuietly(session);
        }
    }

    /**
     * 前端静态资源发布：读取本机发布路径（目录或单文件），
     * 按配置决定是否先打成 zip，再 SFTP 到远程发布目录，最后执行「发布后执行命令」。
     */
    public DeployTask deployFrontendAsync(String targetId) {
        DeployTarget target = resolveTarget(targetId);
        if (!"frontend".equals(target.getType())) {
            throw new IllegalArgumentException("该发布配置不是前端类型，请使用后端发布流程");
        }
        if (ShellUtil.isBlank(target.getFrontendPath())) {
            throw new IllegalArgumentException("前端配置的本机发布路径不能为空");
        }
        File src = new File(target.getFrontendPath().trim());
        if (!src.exists()) {
            throw new IllegalArgumentException("本机发布路径不存在: " + src.getAbsolutePath());
        }
        ServerConfig config = resolveConfig(targetId);
        DeployTask task = createTask(config, target, "DEPLOY");
        declareFrontendSteps(task, target);
        final ServerConfig cfg = config;
        executor.submit(() -> doFrontendDeploy(task, cfg, target, src));
        return task;
    }

    /** 按配置类型分流的文件发布入口：前端类型走静态资源发布，后端类型走 jar 发布（含重启）。用于「选择文件上传」发布 */
    public DeployTask deployFileAsync(String targetId, String localPath, boolean restart) {
        DeployTarget target = resolveTarget(targetId);
        if ("frontend".equals(target.getType())) {
            return deployFrontendFileAsync(targetId, localPath);
        }
        return deployByLocalPathAsync(targetId, localPath, restart);
    }

    /** 前端类型：用本服务磁盘上已有的文件（如分片上传合并产物 / 本机打包产物）发布静态资源，异步返回任务 */
    public DeployTask deployFrontendFileAsync(String targetId, String localPath) {
        DeployTarget target = resolveTarget(targetId);
        if (!"frontend".equals(target.getType())) {
            throw new IllegalArgumentException("该发布配置不是前端类型，请使用后端发布流程");
        }
        if (ShellUtil.isBlank(localPath)) {
            throw new IllegalArgumentException("发布文件路径不能为空");
        }
        File src = new File(localPath.trim());
        if (!src.exists() || !src.isFile()) {
            throw new IllegalArgumentException("发布文件不存在: " + localPath);
        }
        ServerConfig config = resolveConfig(targetId);
        DeployTask task = createTask(config, target, "DEPLOY");
        declareFrontendSteps(task, target);
        final ServerConfig cfg = config;
        final DeployTarget t = target;
        executor.submit(() -> doFrontendDeploy(task, cfg, t, src));
        return task;
    }

    /**
     * 后端发布步骤声明：未勾选「发布前备份」时不声明备份与清理历史备份步骤，
     * 步骤序列为 连接→目录→上传→（清理历史备份）→停止→启动。
     */
    private void declareBackendSteps(DeployTask task, DeployTarget target, boolean restart) {
        if (target.isBackupEnabled()) {
            if (restart) {
                task.declareSteps("连接服务器", "准备远程目录", "备份旧版本", "上传 jar 包", "清理历史备份", "停止应用", "启动应用");
            } else {
                task.declareSteps("连接服务器", "准备远程目录", "备份旧版本", "上传 jar 包", "清理历史备份", "（跳过重启）jar 已就位");
            }
        } else {
            if (restart) {
                task.declareSteps("连接服务器", "准备远程目录", "上传 jar 包", "停止应用", "启动应用");
            } else {
                task.declareSteps("连接服务器", "准备远程目录", "上传 jar 包", "（跳过重启）jar 已就位");
            }
        }
    }

    /** 前端发布步骤声明：勾选「清空后更新」时在备份后插入「清空目标目录」步骤；未勾选「发布前备份」时省略备份步骤 */
    private void declareFrontendSteps(DeployTask task, DeployTarget target) {
        String uploadStep = target.isZip() ? "压缩并上传静态资源" : "上传静态资源";
        List<String> steps = new ArrayList<>();
        steps.add("连接服务器");
        steps.add("准备远程目录");
        if (target.isBackupEnabled()) {
            steps.add("备份旧版本");
        }
        if (target.isClearBeforeDeploy()) {
            steps.add("清空目标目录");
        }
        steps.add(uploadStep);
        steps.add("执行发布后命令");
        task.declareSteps(steps.toArray(new String[0]));
    }

    /** 手动备份：后端类型备份 jar 包，前端类型把发布目录打包成 tar.gz，异步返回任务 */
    public DeployTask backupAsync(String targetId) {
        DeployTarget target = resolveTarget(targetId);
        ServerConfig config = resolveConfig(targetId);
        DeployTask task = createTask(config, target, "BACKUP");
        if ("frontend".equals(target.getType())) {
            task.declareSteps("连接服务器", "备份发布目录");
        } else {
            task.declareSteps("连接服务器", "备份 jar 包");
        }
        final ServerConfig cfg = config;
        executor.submit(() -> doBackup(task, cfg, target));
        return task;
    }

    private void doBackup(DeployTask task, ServerConfig config, DeployTarget target) {
        SshSession session = null;
        try {
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            log.info("[备份] 连接服务器 {}@{}:{} target={}", config.getUsername(), config.getHost(), config.effectivePort(), target.getName());
            session = sshService.open(config);
            stepDone(task, 0, true, config.getUsername() + "@" + config.getHost() + ":" + config.effectivePort(), t);

            t = System.currentTimeMillis();
            stepStart(task, 1);
            CommandResult r = exec(session, config, backupScript(target));
            boolean ok = r.isSuccess();
            stepDone(task, 1, ok, r.getOutput(), t);
            log.info("[备份] 结果: {}", r.getOutput());
            finishTask(task, config, target, "BACKUP", task.startTime, ok ? "备份完成" : "备份失败", ok);
        } catch (Exception e) {
            failTask(task, config, target, e);
        } finally {
            closeQuietly(session);
        }
    }

    // ---------------------------------------------------------------- 启停入口

    public DeployTask startAsync(String targetId) {
        ServerConfig config = resolveConfig(targetId);
        DeployTask task = createTask(config, resolveTarget(targetId), "START");
        task.declareSteps("连接服务器", "启动应用");
        final ServerConfig cfg = config;
        executor.submit(() -> doStart(task, cfg, resolveTarget(targetId)));
        return task;
    }

    public DeployTask stopAsync(String targetId) {
        ServerConfig config = resolveConfig(targetId);
        DeployTask task = createTask(config, resolveTarget(targetId), "STOP");
        task.declareSteps("连接服务器", "停止应用");
        final ServerConfig cfg = config;
        executor.submit(() -> doStop(task, cfg, resolveTarget(targetId)));
        return task;
    }

    public DeployTask restartAsync(String targetId) {
        ServerConfig config = resolveConfig(targetId);
        DeployTask task = createTask(config, resolveTarget(targetId), "RESTART");
        task.declareSteps("连接服务器", "停止应用", "启动应用");
        final ServerConfig cfg = config;
        executor.submit(() -> doRestart(task, cfg, resolveTarget(targetId)));
        return task;
    }

    public DeployTask rollbackAsync(String targetId, String backupName, boolean restart) {
        if (ShellUtil.isBlank(backupName) || backupName.contains("/") || backupName.contains("..")) {
            throw new IllegalArgumentException("备份文件名不合法");
        }
        ServerConfig config = resolveConfig(targetId);
        DeployTask task = createTask(config, resolveTarget(targetId), "ROLLBACK");
        DeployTarget target = resolveTarget(targetId);
        if ("frontend".equals(target.getType())) {
            // 前端类型：恢复发布目录 + 执行发布后命令（无 jar 进程，无需启停）
            task.declareSteps("连接服务器", "恢复备份目录", "执行发布后命令");
        } else if (restart) {
            task.declareSteps("连接服务器", "回滚 jar 包", "停止应用", "启动应用");
        } else {
            task.declareSteps("连接服务器", "回滚 jar 包");
        }
        final ServerConfig cfg = config;
        final String name = backupName.trim();
        executor.submit(() -> doRollback(task, cfg, target, name, restart));
        return task;
    }

    // ---------------------------------------------------------------- 后台执行体

    private void doDeploy(DeployTask task, ServerConfig config, DeployTarget target,
                          LinkedHashMap<String, File> jars, boolean restart, String sourceDesc) {
        SshSession session = null;
        try {
            // 0. 连接
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            log.info("[发布] 连接服务器 {}@{}:{}", config.getUsername(), config.getHost(), config.effectivePort());
            session = sshService.open(config);
            stepDone(task, 0, true, config.getUsername() + "@" + config.getHost() + ":" + config.effectivePort(), t);

            // 1. 目录准备
            t = System.currentTimeMillis();
            stepStart(task, 1);
            log.info("[发布] 准备远程目录 remoteDir={} confDir={} backupDir={}", target.getRemoteDir(),
                    target.getConfDir(), target.effectiveBackupDir());
            // conf 目录仅在显式配置时创建（留空时默认 remoteDir/conf，由 conf 上传时按需创建）
            String confMk = ShellUtil.isNotBlank(target.getConfDir())
                    ? " " + ShellUtil.quote(target.effectiveConfDir()) : "";
            // 日志目录同理：使用自定义启动命令且命令不写日志文件时不创建；error 日志目录仅在显式配置时创建
            String logMk = logDirMk(target);
            CommandResult mk = exec(session, config, "mkdir -p " + ShellUtil.quote(target.getRemoteDir())
                    + confMk
                    + " " + ShellUtil.quote(target.effectiveBackupDir())
                    + logMk);
            if (!mk.isSuccess()) {
                stepDone(task, 1, false, mk.getOutput(), t);
                log.error("[发布] 创建远程目录失败: {}", mk.getOutput());
                finishTask(task, config, target, "DEPLOY", task.startTime, "创建远程目录失败", false);
                return;
            }
            stepDone(task, 1, true, target.getRemoteDir(), t);

            // 2. 备份旧包（可选：勾选「发布前备份」才执行；不勾选则跳过，后续步骤索引整体前移一位）
            if (target.isBackupEnabled()) {
                t = System.currentTimeMillis();
                stepStart(task, 2);
                log.info("[发布] 备份旧版本 jar: {}", target.jarPath());
                CommandResult backup = exec(session, config, backupScript(target));
                stepDone(task, 2, backup.isSuccess(), backup.getOutput(), t);
                log.info("[发布] 备份结果: {}", backup.getOutput());
            }

            // 3. 上传新包（带进度；自定义部署可一次上传多个 jar，逐个传输后统一重启一次）
            final int uploadIdx = target.isBackupEnabled() ? 3 : 2;
            t = System.currentTimeMillis();
            stepStart(task, uploadIdx);
            log.info("[发布] 开始上传 {} → {}", sourceDesc, target.getRemoteDir());
            // 先 SFTP 到中转目录（默认 /tmp，登录用户必能写入；可通过 deploy.staging-dir 改到非 /tmp），
            // 再用 exec 把包移动到目标路径。
            // 这样在需要 root 权限的目录（如 /opt）下，只要开启 su，移动会以 root 身份完成，
            // 避免直接以登录账号 SFTP 到目标目录而报 Permission denied。
            String deployStaging = session.stagingPath("deploy-staging");
            session.mkdirs(deployStaging);
            List<String> uploadedNames = new ArrayList<String>();
            StringBuilder uploadedDetail = new StringBuilder();
            long grandTotal = 0L;
            for (File f : jars.values()) {
                grandTotal += f.length();
            }
            long transferred = 0L;
            int seq = 0;
            for (Map.Entry<String, File> entry : jars.entrySet()) {
                seq++;
                String remoteName = entry.getKey();
                File local = entry.getValue();
                String dest = target.jarPath(remoteName);
                String label = jars.size() > 1 ? ("（" + seq + "/" + jars.size() + " " + remoteName + "）") : null;
                String staging = deployStaging + "/" + UUID.randomUUID().toString().replace("-", "") + ".jar";
                session.upload(local, staging, uploadMonitor(task, uploadIdx, grandTotal, transferred, local.length(), label));
                CommandResult mv = exec(session, config, "cp -f " + ShellUtil.quote(staging)
                        + " " + ShellUtil.quote(dest) + " && echo MOVED && rm -f " + ShellUtil.quote(staging));
                boolean moved = mv.isSuccess() && mv.getOutput().contains("MOVED");
                String detail = sourceDesc + " → " + dest + "（" + readableSize(local.length()) + "）";
                if (!moved) {
                    String hint = ShellUtil.isNotBlank(mv.getOutput()) ? mv.getOutput().trim() : "移动失败（未返回 MOVED）";
                    if (hint.toLowerCase().contains("permission denied")) {
                        hint = config.isSuEnabled()
                                ? "目标目录写权限不足（Permission denied）：su 切换后仍未获得写权限，请检查 su 密码是否正确、目标目录属主是否允许该用户写入。"
                                : "目标目录写权限不足（Permission denied）：登录账号对 " + target.getRemoteDir()
                                + " 没有写权限。请在服务器配置中开启「登录后 su 切换到 root」后重试。";
                    }
                    stepDone(task, uploadIdx, false, detail + "；" + hint, t);
                    log.error("[发布] 上传/移动失败: {}", detail + "；" + hint);
                    finishTask(task, config, target, "DEPLOY", task.startTime, "上传失败：" + hint, false);
                    return;
                }
                uploadedNames.add(remoteName);
                transferred += local.length();
                if (uploadedDetail.length() > 0) {
                    uploadedDetail.append("、");
                }
                uploadedDetail.append(remoteName).append("（").append(readableSize(local.length())).append("）");
                log.info("[发布] 已就位: {}", detail);
            }
            // 自定义部署：把本次上传的 jar 名记入配置，供后续停止 / 启动 / 备份 / 回滚识别多包
            if (target.isCustomDeploy() && !uploadedNames.isEmpty()) {
                try {
                    targetStore.addJarNames(target.getId(), uploadedNames);
                    DeployTarget latest = targetStore.get(target.getId());
                    if (latest != null) {
                        target.setJarNames(latest.getJarNames());
                    }
                } catch (Exception ex) {
                    log.warn("[发布] 记录 jar 列表失败[{}]: {}", target.getId(), ex.getMessage());
                }
            }
            stepDone(task, uploadIdx, true, sourceDesc + " → " + target.getRemoteDir() + "：" + uploadedDetail, t);

            // 4. 清理历史备份（仅在本次有备份时执行，索引随备份步骤有无而变）
            final int cleanIdx = target.isBackupEnabled() ? 4 : 3;
            if (target.isBackupEnabled()) {
                t = System.currentTimeMillis();
                stepStart(task, cleanIdx);
                CommandResult clean = exec(session, config, cleanBackupScript(target));
                stepDone(task, cleanIdx, clean.isSuccess(), "保留最近 " + properties.getKeepBackupCount() + " 个版本", t);
                log.info("[发布] 清理历史备份，保留最近 {} 个版本，结果: {}", properties.getKeepBackupCount(), clean.getOutput());
            }

            // 5/6. 重启
            final int stopIdx = target.isBackupEnabled() ? 5 : 3;
            final int startIdx = target.isBackupEnabled() ? 6 : 4;
            if (restart) {
                log.info("[发布] 开始停止并启动应用 target={}", target.getName());
                doStopSteps(task, session, config, target, stopIdx);
                doStartSteps(task, session, config, target, startIdx);
                finishTask(task, config, target, "DEPLOY", task.startTime,
                        taskAllOk(task) ? "发布完成" : "发布过程中存在失败步骤", taskAllOk(task));
            } else {
                stepStart(task, stopIdx);
                stepDone(task, stopIdx, true, "本次未勾选重启，jar 已就位", 0);
                finishTask(task, config, target, "DEPLOY", task.startTime, "发布完成", true);
            }
        } catch (Exception e) {
            failTask(task, config, target, e);
        } finally {
            closeQuietly(session);
            // 分片上传产生的合并产物位于 uploadDir/chunks/ 下、批量上传产生的临时目录位于 uploadDir/.../<批次>/ 下，
            // 发布（SFTP 已传至目标服务器）后清理，避免磁盘堆积
            for (File f : jars.values()) {
                cleanupLocalTemp(f);
            }
        }
    }

    /** 清理上传流程在本地产生的临时文件（分片合并产物 / 批量上传批次目录），非临时文件不动 */
    private void cleanupLocalTemp(File file) {
        if (file == null) {
            return;
        }
        String path = file.getAbsolutePath();
        try {
            if (path.contains(File.separator + "chunks" + File.separator)) {
                deleteRecursive(file.getParentFile());
            } else if (file.getParentFile() != null && file.getParentFile().getName().endsWith("-batch")) {
                deleteRecursive(file.getParentFile());
            }
        } catch (Exception ignored) {
            // 清理失败不影响主流程
        }
    }

    // ---------------------------------------------------------------- 前端静态资源发布

    private void doFrontendDeploy(DeployTask task, ServerConfig config, DeployTarget target, File src) {
        SshSession session = null;
        File zipFile = null;
        // 步骤序列：连接→目录→（备份）→（清空）→上传→发布后命令。
        // 备份步骤由「发布前备份」开关决定、清空步骤由「清空后更新」开关决定，两者都会使后续步骤索引后移
        int idx = 2;
        final int backupIdx = target.isBackupEnabled() ? idx++ : -1;
        final int cleanIdx = target.isClearBeforeDeploy() ? idx++ : -1;
        final int uploadIdx = idx++;
        final int postIdx = idx;
        try {
            // 0. 连接
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            log.info("[前端发布] 连接服务器 {}@{}:{}", config.getUsername(), config.getHost(), config.effectivePort());
            session = sshService.open(config);
            stepDone(task, 0, true, config.getUsername() + "@" + config.getHost() + ":" + config.effectivePort(), t);

            // 1. 准备远程目录
            t = System.currentTimeMillis();
            stepStart(task, 1);
            CommandResult mk = exec(session, config, "mkdir -p " + ShellUtil.quote(target.getRemoteDir()));
            if (!mk.isSuccess()) {
                stepDone(task, 1, false, mk.getOutput(), t);
                finishTask(task, config, target, "DEPLOY", task.startTime, "创建远程目录失败", false);
                return;
            }
            stepDone(task, 1, true, target.getRemoteDir(), t);

            // 2. 备份旧版本（可选：勾选「发布前备份」才执行；前端把发布目录打包成 tar.gz 到备份目录）
            if (target.isBackupEnabled()) {
                t = System.currentTimeMillis();
                stepStart(task, backupIdx);
                CommandResult backup = exec(session, config, backupScript(target));
                stepDone(task, backupIdx, backup.isSuccess(), backup.getOutput(), t);
            }

            // 2.5 清空目标目录（可选：勾选「清空后更新」时直接删除发布目录/文件夹名整个目录，再重建空目录）
            if (target.isClearBeforeDeploy()) {
                t = System.currentTimeMillis();
                stepStart(task, cleanIdx);
                CommandResult clean = exec(session, config, cleanFrontendScript(target));
                stepDone(task, cleanIdx, clean.isSuccess(), clean.getOutput(), t);
            }

            // 3. 上传
            t = System.currentTimeMillis();
            stepStart(task, uploadIdx);
            String remoteBase = trimSlash(target.getRemoteDir());
            if (target.isZip()) {
                // zip 文件名取「本机发布路径」推导出的文件夹名（如 web.zip），不再写死 dist.zip
                String folder = target.deployFolderName();
                String remoteZip = remoteBase + "/" + (folder != null ? folder : "app") + ".zip";
                SftpProgressMonitor mon;
                long total;
                if (src.isFile() && src.getName().toLowerCase().endsWith(".zip")) {
                    // 用户上传的本身就是 zip：直接作为 <folder>.zip 上传，避免二次打包导致 zip 套 zip
                    zipFile = null;
                    total = src.length();
                    mon = frontendMonitor(task, uploadIdx, total);
                    session.uploadViaStaging(src, remoteZip, mon);
                } else {
                    // 打成 <folder>.zip 上传到发布目录（由 frontendPostCmd 中的 unzip 解压）
                    String name = lastSegment(src.getName());
                    zipFile = File.createTempFile("fe-" + name + "-", ".zip");
                    zipPath(src, zipFile);
                    total = zipFile.length();
                    mon = frontendMonitor(task, uploadIdx, total);
                    session.uploadViaStaging(zipFile, remoteZip, mon);
                }
                // SFTP 直接写入目标目录可能无权限（/opt 等需 root），走中转目录 + su 提权复制
                stepDone(task, uploadIdx, true,
                        "已上传 " + readableSize(total) + " → " + remoteZip, t);
            } else {
                // 直接递归上传整个目录 / 单文件到发布目录
                if (src.isFile()) {
                    long total = src.length();
                    SftpProgressMonitor mon = frontendMonitor(task, uploadIdx, total);
                    String dest = remoteBase + "/" + src.getName();
                    session.uploadViaStaging(src, dest, mon);
                    stepDone(task, uploadIdx, true,
                            "已上传 " + readableSize(total) + " → " + dest, t);
                } else {
                    long total = dirSize(src);
                    SftpProgressMonitor mon = dirMonitor(task, uploadIdx, total);
                    uploadDir(session, config, src, remoteBase, task, uploadIdx, mon);
                    stepDone(task, uploadIdx, true,
                            "已上传目录 " + src.getAbsolutePath() + " → " + remoteBase
                                    + "（" + readableSize(total) + "）", t);
                }
            }

            // 4. 发布后执行命令
            t = System.currentTimeMillis();
            stepStart(task, postIdx);
            String post = target.getFrontendPostCmd();
            if (ShellUtil.isBlank(post)) {
                stepDone(task, postIdx, true, "未配置发布后命令，跳过", t);
            } else {
                CommandResult r = exec(session, config, post);
                // 前端发布：文件已推送完成，发布后命令（解压 / 复制等）无论返回码如何均视为发布成功，
                // 仅在说明中记录返回码与输出，避免解压完成后的附加命令报错导致整体误判失败。
                String detail = r.isSuccess() ? ShellUtil.abbreviate(r.getOutput(), 1000)
                        : "发布后命令返回码 " + r.getExitCode() + "（已忽略，视为发布完成）\n"
                                + ShellUtil.abbreviate(r.getOutput(), 1000);
                stepDone(task, postIdx, true, detail, t);
            }
            finishTask(task, config, target, "DEPLOY", task.startTime, "发布完成", true);
        } catch (Exception e) {
            failTask(task, config, target, e);
        } finally {
            closeQuietly(session);
            if (zipFile != null) {
                try {
                    zipFile.delete();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 目录递归上传：在远程保持相同相对结构 */
    private void uploadDir(SshSession session, ServerConfig config, File localDir, String remoteDir,
                           DeployTask task, int uploadIdx, SftpProgressMonitor monitor) throws Exception {
        File[] children = localDir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            String remote = remoteDir + "/" + c.getName();
            if (c.isDirectory()) {
                exec(session, config, "mkdir -p " + ShellUtil.quote(remote));
                uploadDir(session, config, c, remote, task, uploadIdx, monitor);
            } else {
                // 目标目录可能需 root 才有写权限，走中转目录 + su 提权复制
                session.uploadViaStaging(c, remote, monitor);
            }
        }
    }

    /** 把文件或目录压缩成 zip（目录递归，条目为相对路径） */
    private void zipPath(File src, File zipFile) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            if (src.isFile()) {
                addZipEntry(zos, src, src.getName());
            } else {
                addZipDir(zos, src, src.getName());
            }
        }
    }

    private void addZipDir(ZipOutputStream zos, File dir, String base) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            String entry = base + "/" + c.getName();
            if (c.isDirectory()) {
                zos.putNextEntry(new ZipEntry(entry + "/"));
                zos.closeEntry();
                addZipDir(zos, c, entry);
            } else {
                addZipEntry(zos, c, entry);
            }
        }
    }

    private void addZipEntry(ZipOutputStream zos, File file, String entryName) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            zos.putNextEntry(new ZipEntry(entryName));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
            zos.closeEntry();
        }
    }

    /**
     * 后端 jar 上传进度监控（服务器→目标服务器）：总大小用本地文件大小（已落盘，可靠），
     * 据此算出可信百分比，再结合已传字节与耗时算出实时速度，通过 SSE step 推送给前端 uploadBox 展示。
     *
     * @param label 多包上传时的额外标识，如「（2/3 order.jar）」，单包传 null
     */
    private SftpProgressMonitor uploadMonitor(DeployTask task, int idx, final long grandTotal, final long offset,
                                              final long fileBytes, final String label) {
        return new SftpProgressMonitor() {
            private long sum = 0;
            private long start = System.currentTimeMillis();
            private long lastPush = 0;

            private String head() {
                return "文件已上传到本服务，服务端正在传输到目标服务器" + (label == null ? "" : label) + "… 已传 ";
            }

            public void init(int op, String src, String dest, long max) {
                sum = 0;
                start = System.currentTimeMillis();
                lastPush = start;
                log.info("[发布] 服务端开始向目标服务器传输 jar{}{}", label == null ? "" : label,
                        "（" + readableSize(fileBytes) + "）");
                stepProgress(task, idx, 0, 0,
                        head() + readableSize(offset) + " / " + readableSize(grandTotal));
            }

            public boolean count(long c) {
                sum += c;
                long now = System.currentTimeMillis();
                long elapsed = (now - start) / 1000;
                long bps = elapsed > 0 ? sum / elapsed : 0;
                long done = offset + sum;
                int pct = grandTotal > 0 ? (int) Math.min(100, done * 100 / grandTotal) : 0;
                // 每约 300ms 推送一次，避免高频 SSE 消息
                if (now - lastPush >= 300 || pct >= 100) {
                    lastPush = now;
                    stepProgress(task, idx, pct, bps,
                            head() + readableSize(done) + " / " + readableSize(grandTotal));
                    log.info("[发布] 服务端已传输 {} / {}（{}%） {}", readableSize(done),
                            readableSize(grandTotal), pct, formatSpeed(bps));
                }
                return true;
            }

            public void end() {
                log.info("[发布] 服务端传输完成，本包 {} 共 {}", readableSize(fileBytes), readableSize(sum));
            }
        };
    }

    /** 前端上传通用的进度监控（仅展示百分比与速度，不依赖 jar 路径） */
    private SftpProgressMonitor frontendMonitor(DeployTask task, int idx, long totalBytes) {
        return new SftpProgressMonitor() {
            private long sum = 0;
            private long start = System.currentTimeMillis();
            private long lastPush = 0;

            public void init(int op, String src, String dest, long max) {
                sum = 0;
                start = System.currentTimeMillis();
                lastPush = start;
                stepProgress(task, idx, 0, 0,
                        "文件已上传到本服务，服务端正在传输到目标服务器… 已传 0B / " + readableSize(totalBytes));
            }

            public boolean count(long c) {
                sum += c;
                long now = System.currentTimeMillis();
                long elapsed = (now - start) / 1000;
                long bps = elapsed > 0 ? sum / elapsed : 0;
                int pct = totalBytes > 0 ? (int) Math.min(100, sum * 100 / totalBytes) : 0;
                if (now - lastPush >= 300 || pct >= 100) {
                    lastPush = now;
                    stepProgress(task, idx, pct, bps,
                            "文件已上传到本服务，服务端正在传输到目标服务器… 已传 "
                                    + readableSize(sum) + " / " + readableSize(totalBytes));
                }
                return true;
            }

            public void end() {
                // no-op
            }
        };
    }

    /** 目录递归上传的共享进度 monitor：跨文件累计已传字节，init 不重置累计值 */
    private SftpProgressMonitor dirMonitor(DeployTask task, int idx, long totalBytes) {
        return new SftpProgressMonitor() {
            private long sum = 0;
            private long start = 0;
            private long lastPush = 0;

            public void init(int op, String src, String dest, long max) {
                if (start == 0) {
                    start = System.currentTimeMillis();
                    lastPush = start;
                }
            }

            public boolean count(long c) {
                sum += c;
                long now = System.currentTimeMillis();
                long elapsed = (now - start) / 1000;
                long bps = elapsed > 0 ? sum / elapsed : 0;
                int pct = totalBytes > 0 ? (int) Math.min(100, sum * 100 / totalBytes) : 0;
                if (now - lastPush >= 300 || pct >= 100) {
                    lastPush = now;
                    stepProgress(task, idx, pct, bps,
                            "文件已上传到本服务，服务端正在传输到目标服务器… 已传 "
                                    + readableSize(sum) + " / " + readableSize(totalBytes));
                }
                return true;
            }

            public void end() {
                // no-op
            }
        };
    }

    /** 递归统计文件或目录的总字节数（用于目录上传的进度总量） */
    private static long dirSize(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        File[] children = f.listFiles();
        if (children == null) {
            return 0;
        }
        long sum = 0;
        for (File c : children) {
            sum += dirSize(c);
        }
        return sum;
    }

    private static String lastSegment(String name) {
        if (name == null) {
            return "app";
        }
        int i = name.lastIndexOf('/');
        int j = name.lastIndexOf('\\');
        int k = Math.max(i, j);
        return k >= 0 ? name.substring(k + 1) : name;
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

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    private void doStart(DeployTask task, ServerConfig config, DeployTarget target) {
        SshSession session = null;
        try {
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            log.info("[启动] 连接服务器 {}@{}:{} target={}", config.getUsername(), config.getHost(), config.effectivePort(), target.getName());
            session = sshService.open(config);
            stepDone(task, 0, true, config.getUsername() + "@" + config.getHost() + ":" + config.effectivePort(), t);
            doStartSteps(task, session, config, target, 1);
            log.info("[启动] 执行结果: {}", taskAllOk(task) ? "启动成功" : "启动失败");
            finishTask(task, config, target, "START", task.startTime, taskAllOk(task) ? "启动成功" : "启动失败", taskAllOk(task));
        } catch (Exception e) {
            log.error("[启动] 异常: {}", e.getMessage());
            failTask(task, config, target, e);
        } finally {
            closeQuietly(session);
        }
    }

    private void doStop(DeployTask task, ServerConfig config, DeployTarget target) {
        SshSession session = null;
        try {
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            log.info("[停止] 连接服务器 {}@{}:{} target={}", config.getUsername(), config.getHost(), config.effectivePort(), target.getName());
            session = sshService.open(config);
            stepDone(task, 0, true, config.getUsername() + "@" + config.getHost() + ":" + config.effectivePort(), t);
            doStopSteps(task, session, config, target, 1);
            log.info("[停止] 执行结果: {}", taskAllOk(task) ? "已停止" : "停止失败");
            finishTask(task, config, target, "STOP", task.startTime, taskAllOk(task) ? "已停止" : "停止失败", taskAllOk(task));
        } catch (Exception e) {
            log.error("[停止] 异常: {}", e.getMessage());
            failTask(task, config, target, e);
        } finally {
            closeQuietly(session);
        }
    }

    private void doRestart(DeployTask task, ServerConfig config, DeployTarget target) {
        SshSession session = null;
        try {
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            log.info("[重启] 连接服务器 {}@{}:{} target={}", config.getUsername(), config.getHost(), config.effectivePort(), target.getName());
            session = sshService.open(config);
            stepDone(task, 0, true, config.getUsername() + "@" + config.getHost() + ":" + config.effectivePort(), t);
            doStopSteps(task, session, config, target, 1);
            doStartSteps(task, session, config, target, 2);
            log.info("[重启] 执行结果: {}", taskAllOk(task) ? "重启成功" : "重启失败");
            finishTask(task, config, target, "RESTART", task.startTime, taskAllOk(task) ? "重启成功" : "重启失败", taskAllOk(task));
        } catch (Exception e) {
            log.error("[重启] 异常: {}", e.getMessage());
            failTask(task, config, target, e);
        } finally {
            closeQuietly(session);
        }
    }

    private void doRollback(DeployTask task, ServerConfig config, DeployTarget target, String backupName, boolean restart) {
        SshSession session = null;
        try {
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            log.info("[回滚] 连接服务器 {}@{}:{} target={} backup={} restart={}", config.getUsername(), config.getHost(), config.effectivePort(), target.getName(), backupName, restart);
            session = sshService.open(config);
            stepDone(task, 0, true, config.getUsername() + "@" + config.getHost() + ":" + config.effectivePort(), t);

            String backupFile = target.effectiveBackupDir() + "/" + backupName;
            if ("frontend".equals(target.getType())) {
                doFrontendRollback(task, session, config, target, backupFile, backupName);
            } else {
                // 自定义部署可能包含多个 jar：按备份文件名前缀（<jar名>.<时间戳>）判断要回滚到哪个 jar
                String restoreTo = target.jarPath();
                if (target.isCustomDeploy()) {
                    for (String n : jarNamesOf(target)) {
                        if (ShellUtil.isNotBlank(n) && backupName.startsWith(n + ".")) {
                            restoreTo = target.jarPath(n);
                            break;
                        }
                    }
                }
                t = System.currentTimeMillis();
                stepStart(task, 1);
                CommandResult cr = exec(session, config, "test -f " + ShellUtil.quote(backupFile)
                        + " && cp -p " + ShellUtil.quote(backupFile) + " " + ShellUtil.quote(restoreTo)
                        + " && echo ROLLBACK_OK");
                boolean ok = cr.isSuccess() && cr.getOutput().contains("ROLLBACK_OK");
                stepDone(task, 1, ok, ok ? backupName + " → " + restoreTo : cr.getOutput(), t);
                log.info("[回滚] 回滚结果: {}", ok ? "成功" : cr.getOutput());

                if (ok && restart) {
                    doStopSteps(task, session, config, target, 2);
                    doStartSteps(task, session, config, target, 3);
                }
            }
            finishTask(task, config, target, "ROLLBACK", task.startTime,
                    taskAllOk(task) ? "回滚完成" : "回滚失败", taskAllOk(task));
        } catch (Exception e) {
            log.error("[回滚] 异常: {}", e.getMessage());
            failTask(task, config, target, e);
        } finally {
            closeQuietly(session);
        }
    }

    /** 前端类型回滚：tar 解压备份覆盖前端文件夹（remoteDir/本机发布路径末级目录名，推导文件夹名为空时为 remoteDir），随后执行「发布后命令」 */
    private void doFrontendRollback(DeployTask task, SshSession session, ServerConfig config, DeployTarget target,
                                    String backupFile, String backupName) {
        long t = System.currentTimeMillis();
        stepStart(task, 1);
        CommandResult cr = exec(session, config, "mkdir -p " + ShellUtil.quote(target.effectiveFrontendDir())
                + " && test -f " + ShellUtil.quote(backupFile)
                + " && tar xzf " + ShellUtil.quote(backupFile) + " -C " + ShellUtil.quote(target.effectiveFrontendDir())
                + " && echo ROLLBACK_OK");
        boolean ok = cr.isSuccess() && cr.getOutput().contains("ROLLBACK_OK");
        stepDone(task, 1, ok, ok ? backupName + " → " + target.effectiveFrontendDir() : cr.getOutput(), t);
        log.info("[回滚] 前端目录回滚结果: {}", ok ? "成功" : cr.getOutput());

        t = System.currentTimeMillis();
        stepStart(task, 2);
        String post = target.getFrontendPostCmd();
        if (ShellUtil.isBlank(post)) {
            stepDone(task, 2, true, "未配置发布后命令，跳过", t);
            return;
        }
        CommandResult r = exec(session, config, post);
        stepDone(task, 2, r.isSuccess(), r.getOutput(), t);
    }

    // ---------------------------------------------------------------- 步骤辅助

    private void doStopSteps(DeployTask task, SshSession session, ServerConfig config, DeployTarget target, int idx) {
        long t = System.currentTimeMillis();
        stepStart(task, idx);
        log.info("[停止] 执行停止脚本 target={} jar={}", target.getName(), target.jarPath());
        CommandResult stop = exec(session, config, stopScript(target));
        String out = stop.getOutput();
        boolean ok = stop.isSuccess() && !out.contains("KILL_FAILED");
        String detail;
        if (out.contains("NOT_RUNNING")) {
            detail = "进程未在运行，跳过停止";
        } else if (out.contains("FORCE_KILLED")) {
            detail = "优雅停止超时，已 kill -9 强制结束";
        } else if (out.contains("STOPPED")) {
            detail = "进程已正常退出";
        } else {
            detail = ShellUtil.abbreviate(out, 1000);
        }
        log.info("[停止] 结果: {} | 输出: {}", detail, ShellUtil.abbreviate(out, 500));
        stepDone(task, idx, ok, detail, t);
    }

    private void doStartSteps(DeployTask task, SshSession session, ServerConfig config, DeployTarget target, int idx) {
        long t = System.currentTimeMillis();
        stepStart(task, idx);
        log.info("[启动] 执行启动脚本 target={} jar={} javaCmd={} jvmOpts={} appArgs={}",
                target.getName(), target.jarPath(), target.effectiveJavaCmd(), target.getJvmOpts(), target.getAppArgs());
        CommandResult start = exec(session, config, startScript(target));
        String out = start.getOutput() == null ? "" : start.getOutput();
        // 先以脚本明确标记判断（启动脚本会输出唯一标记 DEPLOY_STARTED_OK / DEPLOY_START_FAILED）
        boolean markedOk = out.contains("DEPLOY_STARTED_OK") && !out.contains("DEPLOY_START_FAILED");
        String pidLine = firstLineWith(out, "DEPLOY_STARTED_OK");
        // 再独立复核进程是否真的存活：用与停止脚本一致的 pid 定位命令单独查询，
        // 避免“脚本标记成功但进程已闪退”或“标记被日志噪音干扰”导致的误判。
        String pid = "";
        try {
            CommandResult probe = exec(session, config, "PID=$(" + pidCommand(target) + "); if [ -n \"$PID\" ]; then echo \"ALIVE $PID\"; else echo NO_PROCESS; fi");
            String po = probe.getOutput() == null ? "" : probe.getOutput();
            if (po.contains("ALIVE")) {
                pid = po.replace("ALIVE", "").trim();
            }
        } catch (Exception e) {
            log.warn("[启动] 进程复核失败，回退到脚本标记判定: {}", e.getMessage());
        }
        boolean alive = !pid.isEmpty();
        // 最终成功 = 脚本标记成功 且 进程复核确实存活；任一不满足都视为失败
        boolean ok = markedOk && alive;
        String detail;
        // 只要真的会用到日志文件（命令重定向 / 显式配置了 info 日志）就提示具体路径，
        // 否则说明日志去处由自定义命令自行决定
        boolean hasLog = target.needsLogFile() || target.hasConfiguredInfoLog();
        if (ok) {
            detail = "进程已启动，" + (pidLine.isEmpty() ? "" : pidLine + "，") + "运行 PID=" + pid
                    + (hasLog ? "，日志: " + target.effectiveLogFile() : "（自定义启动命令，日志输出由命令决定）");
        } else if (markedOk && !alive) {
            detail = "脚本回报启动成功，但复核发现进程未存活（可能启动后闪退）。"
                    + (hasLog ? "请检查日志: " + target.effectiveLogFile()
                              : "请检查自定义启动命令自身的输出（命令未重定向到日志文件）");
            if (target.resolvedStartCommand() != null) {
                detail += "；已使用自定义启动命令，请确认命令已后台运行，且最终 java 进程命令行包含 jar 包名称（否则无法识别进程状态）";
            }
        } else {
            detail = "启动失败：" + ShellUtil.abbreviate(out.replace("DEPLOY_START_FAILED", "进程未存活").trim(), 3000);
            if (target.resolvedStartCommand() != null) {
                detail += "（已使用自定义启动命令：请确认命令能后台拉起进程，且 java 进程命令行包含 jar 包名称）";
            }
        }
        log.info("[启动] 结果: {} | 标记成功={} 进程存活={} pid={}", ok ? "成功" : "失败", markedOk, alive, pid);
        stepDone(task, idx, ok, detail, t);
    }

    private void stepStart(DeployTask task, int idx) {
        StepStatus s = task.step(idx);
        s.status = "RUNNING";
        s.progress = 0;
        task.emitStep(s);
    }

    private void stepProgress(DeployTask task, int idx, int pct, long speed, String detail) {
        StepStatus s = task.step(idx);
        s.progress = pct;
        s.speed = speed;
        if (detail != null) {
            s.detail = detail;
        }
        task.emitStep(s);
    }

    private void stepDone(DeployTask task, int idx, boolean ok, String detail, long startTs) {
        StepStatus s = task.step(idx);
        s.status = ok ? "SUCCESS" : "FAIL";
        s.detail = detail;
        s.costMs = System.currentTimeMillis() - startTs;
        s.progress = 100;
        task.emitStep(s);
    }

    private boolean taskAllOk(DeployTask task) {
        for (StepStatus s : task.steps) {
            if (!"SUCCESS".equals(s.status)) {
                return false;
            }
        }
        return true;
    }

    private void finishTask(DeployTask task, ServerConfig config, DeployTarget target, String action, long begin, String message, boolean success) {
        DeployResult result = toResult(task, message, begin);
        operationLogService.record(config.getId(), displayName(config, target), action, success, result.getCostMs(), message);
        // 发布（DEPLOY）成功：记录最近发布时间并持久化，供「服务总体看板」展示
        if ("DEPLOY".equals(action) && success && target != null) {
            try {
                targetStore.markDeployed(target.getId());
            } catch (Exception e) {
                log.warn("记录最近发布时间失败[{}]: {}", target.getId(), e.getMessage());
            }
        }
        task.complete(result);
    }

    private void failTask(DeployTask task, ServerConfig config, DeployTarget target, Exception e) {
        log.error("任务执行失败[{}]: {}", task.action, e.getMessage(), e);
        // 把正在执行的步骤标记为失败，并附上错误信息
        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase().contains("permission denied") && !config.isSuEnabled()) {
            msg = msg + "（登录账号对目标目录无写权限，请在服务器配置中开启「su 切换到 root」后重试）";
        }
        for (StepStatus s : task.steps) {
            if ("RUNNING".equals(s.status)) {
                s.status = "FAIL";
                s.detail = msg;
                s.progress = 100;
            }
        }
        if (!task.steps.isEmpty() && "PENDING".equals(task.steps.get(0).status)) {
            task.steps.get(0).status = "FAIL";
            task.steps.get(0).detail = msg;
        }
        // 构建完整结果并通过 done 事件下发给前端：前端统一用 renderResult 重绘全部步骤，
        // 失败的步骤会显示红色 ✗，不再遗留“执行中/上传中”的转圈状态。
        DeployResult result = toResult(task, msg, task.startTime);
        operationLogService.record(config.getId(), displayName(config, target), task.action, false, result.getCostMs(), msg);
        task.complete(result);
    }

    private DeployResult toResult(DeployTask task, String message, long begin) {
        DeployResult r = new DeployResult();
        boolean ok = taskAllOk(task);
        r.setMessage(message);
        r.setSuccess(ok);
        r.setCostMs(System.currentTimeMillis() - begin);
        for (StepStatus s : task.steps) {
            r.step(s.name, "SUCCESS".equals(s.status), s.detail, s.costMs);
        }
        return r;
    }

    // ---------------------------------------------------------------- 状态 / 日志 / 备份（同步）

    public AppStatus status(String targetId) {
        ServerConfig config = resolveConfig(targetId);
        DeployTarget target = resolveTarget(targetId);
        AppStatus status = new AppStatus();
        status.setServerId(config.getId());
        status.setServerName(config.getName());
        status.setTargetId(target.getId());
        status.setTargetName(target.getName());
        status.setJarPath(target.jarPath());
        status.setLogFile(target.effectiveInfoLogFile());
        status.setErrorLogFile(target.effectiveErrorLogFile());
        SshSession session = null;
        try {
            session = sshService.open(config);
            status.setConnected(true);
            CommandResult r = exec(session, config, statusScript(target));
            Map<String, String> kv = parseKv(r.getOutput());
            String pid = kv.get("PID");
            status.setRunning(ShellUtil.isNotBlank(pid));
            status.setPid(pid);
            status.setStartTime(kv.get("START"));
            status.setUptime(kv.get("UPTIME"));
            String rss = kv.get("RSS");
            if (ShellUtil.isNotBlank(rss)) {
                try {
                    status.setMemory(readableSize(Long.parseLong(rss.trim()) * 1024L));
                } catch (NumberFormatException ignored) {
                    status.setMemory(rss);
                }
            }
            status.setJarInfo(kv.get("JAR"));
            status.setMessage(status.isRunning() ? "运行中" : "未运行");
            return status;
        } catch (Exception e) {
            status.setConnected(false);
            status.setRunning(false);
            status.setMessage("连接失败: " + e.getMessage());
            return status;
        } finally {
            closeQuietly(session);
        }
    }

    /** 关键词检索时最多扫描的日志尾部字节数（避免超大日志全量 grep 卡住） */
    private static final long LOG_SEARCH_MAX_BYTES = 20L * 1024 * 1024;

    /**
     * 查看运行日志。
     *
     * @param type info / error，默认 info
     * @param q    关键词，可为空；多个关键词用空格分隔（AND，需同时命中），含空格的关键词用双引号包裹，
     *             如 {@code ERROR "2026-11-25 17:30"}
     */
    public String tailLog(String targetId, String type, int lines, String q) {
        int n = lines <= 0 ? 200 : Math.min(lines, 2000);
        ServerConfig config = resolveConfig(targetId);
        DeployTarget target = resolveTarget(targetId);
        boolean errorLog = "error".equalsIgnoreCase(type == null ? "" : type.trim());
        String path = target.logFileOf(type);
        // 不再因为「自定义启动命令未引用日志」就直接拒绝读取：
        // 日志可能由应用日志框架（logback 等）写入固定路径，只要文件在就照常读；
        // 文件不存在时再在提示里说明可能的原因。
        List<String> keywords = splitKeywords(q);
        SshSession session = null;
        try {
            session = sshService.open(config);
            StringBuilder script = new StringBuilder();
            script.append("if [ ! -f ").append(ShellUtil.quote(path)).append(" ]; then echo ")
                    .append(ShellUtil.quote("日志文件不存在: " + path)).append("; exit 0; fi\n");
            if (keywords.isEmpty()) {
                script.append("tail -n ").append(n).append(' ').append(ShellUtil.quote(path));
            } else {
                // 先截取日志尾部一段，再逐关键词 grep（链式管道 = AND），最后取最新 n 行
                script.append("tail -c ").append(LOG_SEARCH_MAX_BYTES).append(' ').append(ShellUtil.quote(path));
                for (String kw : keywords) {
                    script.append(" | grep -i -F -e ").append(ShellUtil.quote(kw));
                }
                script.append(" | tail -n ").append(n);
            }
            CommandResult r = exec(session, config, script.toString());
            String out = r.getOutput() == null ? "" : r.getOutput();
            String trimmed = out.trim();
            // 文件不存在：说明日志文件路径，并按当前配置补充可能的原因
            if (trimmed.startsWith("日志文件不存在")) {
                StringBuilder msg = new StringBuilder(trimmed);
                if (errorLog) {
                    msg.append("\n请确认日志框架已把 error 日志写入该路径，或在发布配置里修改「error 日志文件」。");
                } else if (!target.needsLogFile()) {
                    msg.append("\n当前配置使用自定义启动命令，且命令未把输出重定向到日志文件；")
                            .append("如果日志由应用日志框架（logback 等）写入，请确认发布配置里的「info 日志文件」路径正确：")
                            .append(target.effectiveInfoLogFile());
                }
                return msg.toString();
            }
            if (keywords.isEmpty()) {
                return out;
            }
            if (trimmed.isEmpty()) {
                return "未找到匹配内容（关键词：" + joinKeywords(keywords) + "）。\n可尝试减少关键词或调整行数。";
            }
            return out;
        } finally {
            closeQuietly(session);
        }
    }

    /**
     * 拆分日志检索关键词：空格分隔多个条件（AND），双引号 / 单引号包裹的内容作为一个整体，
     * 例如 {@code ERROR "2026-11-25 17:30"} → [ERROR, 2026-11-25 17:30]。
     */
    private static List<String> splitKeywords(String q) {
        List<String> list = new ArrayList<String>();
        if (ShellUtil.isBlank(q)) {
            return list;
        }
        Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(q.trim());
        while (m.find()) {
            String token = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
            if (token != null && !token.trim().isEmpty() && list.size() < 10) {
                list.add(token.trim());
            }
        }
        return list;
    }

    private static String joinKeywords(List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        for (String k : keywords) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(k.contains(" ") ? "\"" + k + "\"" : k);
        }
        return sb.toString();
    }

    /** 需要提前创建的日志目录（info 日志；显式配置的 error 日志），返回已带前导空格的 shell 片段 */
    private String logDirMk(DeployTarget t) {
        StringBuilder sb = new StringBuilder();
        if (t.needsLogFile()) {
            String p = parentDir(t.effectiveInfoLogFile());
            if (ShellUtil.isNotBlank(p)) {
                sb.append(' ').append(ShellUtil.quote(p));
            }
        }
        if (t.hasConfiguredErrorLog()) {
            String p = parentDir(t.effectiveErrorLogFile());
            if (ShellUtil.isNotBlank(p) && sb.indexOf(ShellUtil.quote(p)) < 0) {
                sb.append(' ').append(ShellUtil.quote(p));
            }
        }
        return sb.toString();
    }

    public List<Map<String, String>> listBackups(String targetId) {
        ServerConfig config = resolveConfig(targetId);
        DeployTarget target = resolveTarget(targetId);
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        SshSession session = null;
        try {
            session = sshService.open(config);
            CommandResult r = exec(session, config, listBackupScript(target));
            String[] lines = r.getOutput().split("\n");
            for (String line : lines) {
                String s = line.trim();
                if (s.length() == 0 || !s.contains("|")) {
                    continue;
                }
                String[] parts = s.split("\\|", -1);
                Map<String, String> item = new LinkedHashMap<String, String>();
                item.put("name", parts[0]);
                item.put("size", parts.length > 1 ? parts[1] : "");
                item.put("time", parts.length > 2 ? parts[2] : "");
                list.add(item);
            }
            return list;
        } finally {
            closeQuietly(session);
        }
    }

    /** 连接测试：验证账号密码是否可用，并查看远端 java 版本（服务器级） */
    public Map<String, Object> testConnection(String serverId) {
        long begin = System.currentTimeMillis();
        ServerConfig config = store.getForConnect(serverId);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        SshSession session = null;
        try {
            session = sshService.open(config);
            CommandResult who = session.exec("echo CONNECT_OK; whoami; pwd");
            CommandResult java = exec(session, config, "java -version 2>&1 | head -3");
            map.put("success", Boolean.TRUE);
            map.put("login", who.getOutput());
            map.put("java", java.getOutput());
            map.put("remoteDir", "（发布目录请在各发布配置中维护）");
            if (config.isSuEnabled()) {
                CommandResult suWho = exec(session, config, "echo SU_OK; whoami");
                boolean suOk = suWho.isSuccess() && suWho.getOutput().contains("SU_OK");
                map.put("suEnabled", Boolean.TRUE);
                map.put("suUser", config.effectiveSuUser());
                map.put("su", (suOk ? "su 切换成功，当前身份: " : "su 切换失败: ") + suWho.getOutput());
            }
            operationLogService.record(config, "TEST", true, System.currentTimeMillis() - begin, "连接成功");
            return map;
        } catch (Exception e) {
            map.put("success", Boolean.FALSE);
            map.put("message", e.getMessage());
            operationLogService.record(config, "TEST", false, System.currentTimeMillis() - begin, e.getMessage());
            return map;
        } finally {
            closeQuietly(session);
        }
    }

    // ---------------------------------------------------------------- 解析辅助

    /** 按发布配置 id 解析：返回其所属服务器（连接凭据已解密） */
    public ServerConfig resolveConfig(String targetId) {
        DeployTarget target = targetStore.get(targetId);
        if (target == null) {
            throw new IllegalArgumentException("发布配置不存在");
        }
        return store.getForConnect(target.getServerId());
    }

    public DeployTarget resolveTarget(String targetId) {
        DeployTarget target = targetStore.get(targetId);
        if (target == null) {
            throw new IllegalArgumentException("发布配置不存在");
        }
        String name = target.getName() == null ? targetId : target.getName();
        if ("frontend".equals(target.getType())) {
            // 前端静态资源：只需要发布目录与本机发布路径，不需要 jar 名称
            if (ShellUtil.isBlank(target.getRemoteDir()) || ShellUtil.isBlank(target.getFrontendPath())) {
                throw new IllegalArgumentException("发布配置「" + name
                        + "」尚未填写发布目录与本机发布路径，请先编辑发布配置");
            }
        } else if (target.isCustomDeploy()) {
            // 自定义部署：jar 名称由上传的文件自动识别，首次上传前允许为空，因此这里只校验收 jar 包目录
            if (ShellUtil.isBlank(target.getRemoteDir())) {
                throw new IllegalArgumentException("发布配置「" + name + "」尚未填写 jar 包目录，请先编辑发布配置");
            }
        } else if (ShellUtil.isBlank(target.getRemoteDir()) || ShellUtil.isBlank(target.getJarName())) {
            throw new IllegalArgumentException("发布配置「" + name
                    + "」尚未填写 jar 包目录与 jar 名称，请先编辑发布配置");
        }
        return target;
    }

    private static String displayName(ServerConfig config, DeployTarget target) {
        String server = config == null ? "" : config.getName();
        String tname = target == null ? "" : target.getName();
        if (ShellUtil.isNotBlank(server) && ShellUtil.isNotBlank(tname)) {
            return server + " / " + tname;
        }
        return ShellUtil.isNotBlank(server) ? server : tname;
    }

    // ---------------------------------------------------------------- 任务创建

    /**
     * 在共享 taskMap 中登记一个服务器级任务（JDK 安装等不绑定发布配置的操作复用此能力），
     * 使其可被 {@code /api/deploy/tasks/{taskId}/stream} 的 SSE 端点正常订阅进度。
     */
    public DeployTask createServerTask(ServerConfig config, String action) {
        DeployTask t = new DeployTask();
        t.taskId = UUID.randomUUID().toString().replace("-", "");
        t.serverId = config.getId();
        t.serverName = config.getName();
        t.action = action;
        t.startTime = System.currentTimeMillis();
        taskMap.put(t.taskId, t);
        lastTaskByTarget.put(config.getId(), t.taskId);
        return t;
    }

    /**
     * 登记一个集群级任务（如 Nacos 集群部署跨多台服务器），
     * serverId/serverName 仅用于展示与 lastTaskByTarget 索引，使其可被 SSE 端点正常订阅进度。
     */
    public DeployTask createClusterTask(String action, String title) {
        DeployTask t = new DeployTask();
        t.taskId = UUID.randomUUID().toString().replace("-", "");
        t.serverId = title;
        t.serverName = title;
        t.action = action;
        t.startTime = System.currentTimeMillis();
        taskMap.put(t.taskId, t);
        lastTaskByTarget.put(title, t.taskId);
        return t;
    }

    private synchronized DeployTask createTask(ServerConfig config, DeployTarget target, String action) {
        // 同一发布配置同时只允许一个任务：连点「一键发布」会让两个任务交叉执行
        // （A 刚启动、B 又把它停掉），两套配置之间不受影响，但同一个配置内必须串行
        String running = runningAction(target.getId());
        if (running != null) {
            throw new IllegalStateException("该发布配置正在执行「" + running + "」，请等待当前任务结束后再操作");
        }
        DeployTask t = new DeployTask();
        t.taskId = UUID.randomUUID().toString().replace("-", "");
        t.serverId = config.getId();
        t.serverName = displayName(config, target);
        t.action = action;
        t.startTime = System.currentTimeMillis();
        taskMap.put(t.taskId, t);
        lastTaskByTarget.put(target.getId(), t.taskId);
        return t;
    }

    /** 该发布配置是否有尚未结束的任务；有则返回其动作名，否则返回 null */
    private String runningAction(String targetId) {
        String lastId = lastTaskByTarget.get(targetId);
        if (lastId == null) {
            return null;
        }
        DeployTask last = taskMap.get(lastId);
        return last != null && !last.isFinished() ? last.action : null;
    }

    // ---------------------------------------------------------------- 远程脚本

    private String startScript(DeployTarget t) {
        List<String> names = t.effectiveJarNames();
        // 填写了「自定义启动命令」时，用它完全覆盖默认启动脚本：
        // 不再自动 cd 到 jar 目录、不再校验 jar 是否存在（命令可能压根不用 jar，例如 systemctl / 启动脚本）
        String custom = t.resolvedStartCommand();
        boolean useCustom = custom != null;
        String pidFile = ShellUtil.quote(t.effectivePidFile());
        int waitSeconds = Math.max(1, properties.getStartWaitSeconds());
        StringBuilder sb = new StringBuilder();
        sb.append("set -e\n");
        // 只有确实会写日志文件时才建日志目录：自定义启动命令不再默认重定向到日志文件，
        // 若无日志占位符 / 日志路径引用，就不该凭空创建 logs 目录
        String md = logDirMk(t);
        if (!md.isEmpty()) {
            sb.append("mkdir -p").append(md).append("\n");
        }
        if (!useCustom) {
            sb.append("cd ").append(ShellUtil.quote(t.getRemoteDir())).append("\n");
            if (names.isEmpty()) {
                sb.append("echo 'START_FAILED 尚未上传 jar 包（jar 名称由上传文件自动识别），请先上传后再启动'; exit 1\n");
            } else {
                for (String n : names) {
                    sb.append("if [ ! -f ").append(ShellUtil.quote(t.jarPath(n))).append(" ]; then echo 'START_FAILED jar 不存在: ")
                            .append(t.jarPath(n)).append("'; exit 1; fi\n");
                }
            }
        }
        // 自定义命令与后续步骤不使用 set -e，避免 source /etc/profile 返回非 0 时中断启动
        sb.append("set +e\n");
        if (ShellUtil.isNotBlank(t.getPreStartCmd())) {
            sb.append(t.getPreStartCmd().trim()).append("\n");
        }
        if (useCustom) {
            // 自定义启动命令：占位符已替换，按原样执行（不附加 cd，工作目录为登录用户家目录）
            sb.append(custom.trim()).append("\n");
            sb.append("sleep ").append(waitSeconds).append("\n");
            sb.append("PID=$(").append(pidCommand(t)).append(")\n");
            sb.append("if [ -z \"$PID\" ]; then echo 'DEPLOY_START_FAILED 进程未存活'; exit 1; fi\n");
            // 自定义命令不保证是后台操作，$! 不可靠，直接写复核到的真实 PID 供停止脚本使用
            sb.append("echo \"$PID\" > ").append(pidFile).append("\n");
        } else {
            String jar = ShellUtil.quote(t.jarPath());
            sb.append("nohup ").append(ShellUtil.quote(t.effectiveJavaCmd())).append(' ')
                    .append(ShellUtil.trimToEmpty(t.getJvmOpts())).append(" -jar ").append(jar).append(' ')
                    .append(ShellUtil.trimToEmpty(t.getAppArgs()))
                    .append(" >> ").append(ShellUtil.quote(t.effectiveInfoLogFile())).append(" 2>&1 &\n");
            sb.append("echo $! > ").append(pidFile).append("\n");
            sb.append("sleep ").append(waitSeconds).append("\n");
            sb.append("PID=$(").append(pidCommand(t)).append(")\n");
            // 用唯一且稳定的标记表达启动结果，避免应用自身日志里的 FAIL/ERROR 字样被误判为启动失败。
            // 标记单独成行、前缀固定，便于前端/后端精确匹配，不混入应用日志尾部内容。
            sb.append("if [ -z \"$PID\" ]; then echo 'DEPLOY_START_FAILED 进程未存活'; exit 1; fi\n");
        }
        sb.append("echo \"DEPLOY_STARTED_OK pid=$PID\"\n");
        if (ShellUtil.isNotBlank(t.getPostStartCmd())) {
            sb.append(t.getPostStartCmd().trim()).append("\n");
        }
        return sb.toString();
    }

    private String stopScript(DeployTarget t) {
        int wait = Math.max(3, properties.getStopWaitSeconds());
        String pidFile = ShellUtil.quote(t.effectivePidFile());
        String nameRe = ShellUtil.quote(jarRegex(t, false));
        StringBuilder sb = new StringBuilder();
        // 先按发布目录精确定位，再与 pid 文件合并去重：
        // 同机同账号下可能有生产/测试两套同名 jar，只按文件名杀会把两套一起干掉
        sb.append("PIDS=$(").append(pidListCommand(t)).append(")\n");
        sb.append("PF=").append(pidFile).append("\n");
        sb.append("if [ -f \"$PF\" ]; then FP=$(cat \"$PF\" 2>/dev/null | tr -d ' \\t\\r\\n' | head -1); ");
        sb.append("if [ -n \"$FP\" ] && kill -0 \"$FP\" 2>/dev/null; then ");
        sb.append("case \" $PIDS \" in *\" $FP \"*) ;; *) ");
        // pid 文件可能与实际不符（进程重启后残留），只有定位不到进程、
        // 或该 PID 的命令行确实含本配置的 jar 名时才纳入，避免误杀无关进程
        sb.append("if [ -z \"$PIDS\" ]; then PIDS=\"$FP\"; ");
        sb.append("elif ps -o args= -p $FP 2>/dev/null | grep -qE ").append(nameRe);
        sb.append("; then PIDS=\"$PIDS $FP\"; fi ;; esac; fi; fi\n");
        sb.append("PIDS=$(echo $PIDS)\n");
        sb.append("if [ -z \"$PIDS\" ]; then echo 'NOT_RUNNING 没有正在运行的进程'; rm -f \"$PF\"; exit 0; fi\n");
        sb.append("echo \"停止进程 pid=$PIDS\"\n");
        sb.append("kill $PIDS 2>/dev/null\n");
        // 等待优雅退出
        sb.append("i=0\n");
        sb.append("while [ $i -lt ").append(wait).append(" ]; do\n");
        sb.append("  LEFT=''\n");
        sb.append("  for q in $PIDS; do kill -0 \"$q\" 2>/dev/null && LEFT=\"$LEFT $q\"; done\n");
        sb.append("  if [ -z \"$(echo $LEFT)\" ]; then echo STOPPED; rm -f \"$PF\"; exit 0; fi\n");
        sb.append("  sleep 1\n");
        sb.append("  i=$((i+1))\n");
        sb.append("done\n");
        // 优雅退出超时：只强杀本次定位到的 PID，不用 pkill -f，避免连同名 jar 的其它环境一起杀掉
        sb.append("echo '优雅停止超时，强制结束'\n");
        sb.append("kill -9 $LEFT 2>/dev/null\n");
        sb.append("sleep 1\n");
        sb.append("LEFT=''\n");
        sb.append("for q in $PIDS; do kill -0 \"$q\" 2>/dev/null && LEFT=\"$LEFT $q\"; done\n");
        sb.append("if [ -n \"$(echo $LEFT)\" ]; then echo KILL_FAILED; rm -f \"$PF\"; exit 1; fi\n");
        sb.append("rm -f \"$PF\"\n");
        sb.append("echo FORCE_KILLED\n");
        return sb.toString();
    }

    private String statusScript(DeployTarget t) {
        StringBuilder sb = new StringBuilder();
        // 先按发布目录精确定位真实进程（最准确）；定位不到再回退启动时写入的 pid 文件
        sb.append("PID=$(").append(pidCommand(t)).append(")\n");
        sb.append("PF=").append(ShellUtil.quote(t.effectivePidFile())).append("\n");
        sb.append("if [ -z \"$PID\" ] && [ -f \"$PF\" ]; then P=$(cat \"$PF\" 2>/dev/null | tr -d ' \\t\\r\\n' | head -1); ");
        sb.append("if [ -n \"$P\" ] && kill -0 \"$P\" 2>/dev/null; then PID=\"$P\"; fi; fi\n");
        sb.append("echo \"PID|$PID\"\n");
        sb.append("if [ -n \"$PID\" ]; then\n");
        // 启动时间统一输出 YYYY-MM-DD HH:mm:ss：
        // 优先取 /proc/<pid> 的修改时间（即进程创建时间），避免 ps lstart 的英文/本地化格式（Wed Nov 25 16:12:07 2026）；
        // date -r 不可用时退回 stat -c %y，最后才回退 ps lstart（由前端兜底格式化）。
        sb.append("  ST=$(date -r /proc/$PID '+%Y-%m-%d %H:%M:%S' 2>/dev/null)\n");
        sb.append("  [ -z \"$ST\" ] && ST=$(stat -c '%y' /proc/$PID 2>/dev/null | cut -d. -f1)\n");
        sb.append("  [ -z \"$ST\" ] && ST=$(ps -o lstart= -p $PID 2>/dev/null | sed 's/^ *//')\n");
        sb.append("  echo \"START|$ST\"\n");
        sb.append("  echo \"UPTIME|$(ps -o etime= -p $PID 2>/dev/null | tr -d ' ')\"\n");
        sb.append("  echo \"RSS|$(ps -o rss= -p $PID 2>/dev/null | tr -d ' ')\"\n");
        sb.append("fi\n");
        // jar 信息：自定义部署可能包含多个 jar，逐个取大小与时间后拼成一行
        sb.append("JI=''\n");
        for (String n : jarNamesOf(t)) {
            if (ShellUtil.isBlank(n)) {
                continue;
            }
            sb.append("JI=\"$JI ").append(n).append(": $(ls -lh ").append(ShellUtil.quote(t.jarPath(n)))
                    .append(" 2>/dev/null | awk '{print $5\" \"$6\" \"$7\" \"$8}')\"\n");
        }
        sb.append("echo \"JAR|$JI\"\n");
        return sb.toString();
    }

    private String backupScript(DeployTarget t) {
        if ("frontend".equals(t.getType())) {
            return frontendBackupScript(t);
        }
        // 自定义部署可能包含多个 jar：逐个备份为 <jar名>.<时间戳>
        StringBuilder sb = new StringBuilder();
        sb.append("TS=$(date +%Y%m%d%H%M%S)\n");
        sb.append("mkdir -p ").append(ShellUtil.quote(t.effectiveBackupDir())).append("\n");
        sb.append("CNT=0\n");
        for (String n : jarNamesOf(t)) {
            if (ShellUtil.isBlank(n)) {
                continue;
            }
            sb.append("if [ -f ").append(ShellUtil.quote(t.jarPath(n))).append(" ]; then\n");
            sb.append("  cp -p ").append(ShellUtil.quote(t.jarPath(n))).append(' ')
                    .append(ShellUtil.quote(t.effectiveBackupDir() + "/" + n + "."))
                    .append("\"$TS\"").append(" && echo \"已备份为 ").append(n).append(".$TS\" && CNT=$((CNT+1))\n");
            sb.append("fi\n");
        }
        sb.append("if [ $CNT -eq 0 ]; then echo '远程暂无旧版本，跳过备份'; fi\n");
        return sb.toString();
    }

    /** 前端类型备份：只把发布目录里的内容（不含外层目录层级）打包成 tar.gz，放到备份目录。
     *  注意用「-C 发布目录 .」打包内容本身，与回滚时的「tar xzf -C 发布目录」解压保持一致，
     *  避免包内多一层目录导致回滚时套娃。备份目录在发布目录内时按相对路径排除。 */
    private String frontendBackupScript(DeployTarget t) {
        String dir = trimSlash(t.effectiveFrontendDir());
        String base = lastSegment(dir);
        String backupDir = trimSlash(t.effectiveBackupDir());
        // 备份目录位于发布目录内时，按相对 -C 目录（发布目录本身）的路径排除，tar 的 --exclude 需要相对路径才匹配
        String excludeRel = null;
        if (backupDir.equals(dir)) {
            // 备份文件直接写在发布目录内，排除所有历史备份包，避免把自己打进包
            excludeRel = "*.tar.gz";
        } else if (backupDir.startsWith(dir + "/")) {
            excludeRel = backupDir.substring(dir.length() + 1);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("if [ -d ").append(ShellUtil.quote(dir))
                .append(" ] && [ \"$(ls -A ").append(ShellUtil.quote(dir)).append(" 2>/dev/null)\" ]; then\n");
        sb.append("  TS=$(date +%Y%m%d%H%M%S)\n");
        sb.append("  mkdir -p ").append(ShellUtil.quote(backupDir)).append("\n");
        sb.append("  tar czf ").append(ShellUtil.quote(backupDir)).append('/').append(base).append(".$TS.tar.gz")
                .append(" -C ").append(ShellUtil.quote(dir));
        if (excludeRel != null) {
            sb.append(" --exclude=").append(ShellUtil.quote(excludeRel));
        }
        sb.append(" .");
        sb.append(" && echo \"已备份为 ").append(base).append(".$TS.tar.gz\"\n");
        sb.append("else\n");
        sb.append("  echo '远程发布目录为空或不存在，跳过备份'\n");
        sb.append("fi\n");
        return sb.toString();
    }

    /**
     * 前端类型「清空后更新」：直接删除发布目录（effectiveFrontendDir = 发布目录/文件夹名）整个目录，
     * 再重建空目录，让随后的上传 / 解压得到一个干净的目录，旧版本已删除的文件不会残留。
     * 备份逻辑保持原样（发布前已按 effectiveFrontendDir 打包到备份目录）。
     * 安全保护：仅当「文件夹名」为安全的子目录名时才会 rm -rf；
     * 文件夹名为空（目标即发布根目录本身，会连备份目录一并删除）或包含路径分隔符 / .. 时，
     * 禁止删除，跳过清空并按覆盖更新执行。
     */
    private String cleanFrontendScript(DeployTarget t) {
        String dir = trimSlash(t.effectiveFrontendDir());
        // 文件夹名由「本机发布路径」末级目录名推导，仅含最后一级、不含路径分隔符，只需防范 ".."
        String fn = t.deployFolderName();
        StringBuilder sb = new StringBuilder();
        boolean safe = !ShellUtil.isBlank(fn) && !fn.contains("..");
        if (safe) {
            sb.append("if [ -d ").append(ShellUtil.quote(dir)).append(" ]; then\n");
            sb.append("  rm -rf -- ").append(ShellUtil.quote(dir)).append("\n");
            sb.append("  echo '已删除发布目录: ").append(dir).append("'\n");
            sb.append("else\n");
            sb.append("  echo '发布目录不存在，跳过删除'\n");
            sb.append("fi\n");
            // 重建空目录，保证随后的 SFTP 上传 / cd 解压流程可用
            sb.append("mkdir -p ").append(ShellUtil.quote(dir)).append("\n");
            sb.append("echo '已重建发布目录: ").append(dir).append("'\n");
        } else {
            // 文件夹名为空时 effectiveFrontendDir 就是发布根目录本身，rm -rf 会连备份目录一并删除，
            // 为安全起见跳过清空，本次按覆盖更新执行
            sb.append("echo '文件夹名为空，目标为发布根目录，为安全起见跳过清空，本次按覆盖更新执行'\n");
        }
        return sb.toString();
    }

    private String cleanBackupScript(DeployTarget t) {
        int keep = Math.max(1, properties.getKeepBackupCount());
        if ("frontend".equals(t.getType())) {
            StringBuilder fb = new StringBuilder();
            fb.append("cd ").append(ShellUtil.quote(t.effectiveBackupDir())).append(" 2>/dev/null || exit 0\n");
            fb.append("ls -1t *.tar.gz 2>/dev/null | tail -n +").append(keep + 1)
                    .append(" | while read f; do rm -f \"$f\"; echo \"已清理 $f\"; done\n");
            return fb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("cd ").append(ShellUtil.quote(t.effectiveBackupDir())).append(" 2>/dev/null || exit 0\n");
        // 备份文件名为 <jar名>.<时间戳>，多 jar 时用扩展正则匹配任一 jar 名的前缀
        sb.append("ls -1t | grep -E ").append(ShellUtil.quote(backupPrefixRegex(t)))
                .append(" | tail -n +").append(keep + 1)
                .append(" | while read f; do rm -f \"$f\"; echo \"已清理 $f\"; done\n");
        return sb.toString();
    }

    private String listBackupScript(DeployTarget t) {
        if ("frontend".equals(t.getType())) {
            StringBuilder fb = new StringBuilder();
            fb.append("cd ").append(ShellUtil.quote(t.effectiveBackupDir())).append(" 2>/dev/null || exit 0\n");
            fb.append("ls -1t *.tar.gz 2>/dev/null | head -30 | while read f; do\n");
            fb.append("  echo \"$f|$(du -h \"$f\" 2>/dev/null | awk '{print $1}')|");
            fb.append("$(date -r \"$f\" '+%Y-%m-%d %H:%M:%S' 2>/dev/null)\"\n");
            fb.append("done\n");
            return fb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("cd ").append(ShellUtil.quote(t.effectiveBackupDir())).append(" 2>/dev/null || exit 0\n");
        sb.append("ls -1t | grep -E ").append(ShellUtil.quote(backupPrefixRegex(t)))
                .append(" | head -30 | while read f; do\n");
        sb.append("  echo \"$f|$(du -h \"$f\" 2>/dev/null | awk '{print $1}')|");
        sb.append("$(date -r \"$f\" '+%Y-%m-%d %H:%M:%S' 2>/dev/null)\"\n");
        sb.append("done\n");
        return sb.toString();
    }

    /** 备份文件名的匹配正则（形如 ^(a\.jar\.|b\.jar\.)），用于多 jar 场景下列出 / 清理历史备份 */
    private static String backupPrefixRegex(DeployTarget t) {
        StringBuilder sb = new StringBuilder("^(");
        for (String n : jarNamesOf(t)) {
            if (ShellUtil.isBlank(n)) {
                continue;
            }
            if (sb.length() > 2) {
                sb.append('|');
            }
            sb.append(regexEscape(n)).append("\\.");
        }
        if (sb.length() == 2) {
            return "^deploy-target-no-jar";
        }
        return sb.append(')').toString();
    }

    /**
     * 该配置参与进程识别 / 备份 / 回滚的 jar 文件名列表。
     * 自定义部署可能包含多个 jar；默认部署仅有一个。
     */
    private static List<String> jarNamesOf(DeployTarget t) {
        List<String> names = t.effectiveJarNames();
        if (names.isEmpty()) {
            names.add("");
        }
        return names;
    }

    /** 把 jar 文件名列表拼成 pgrep / grep -E 可用的扩展正则（转义元字符，避免点号被当成通配） */
    private static String jarRegex(DeployTarget t, boolean fullPath) {
        StringBuilder sb = new StringBuilder();
        for (String n : jarNamesOf(t)) {
            if (ShellUtil.isBlank(n)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(regexEscape(fullPath ? t.jarPath(n) : n));
        }
        // 无任何 jar 名称时给一个不可能匹配的占位，避免空正则匹配到所有进程
        return sb.length() == 0 ? "deploy-target-no-jar" : sb.toString();
    }

    /** 正则元字符转义 */
    private static String regexEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 定位运行指定发布配置的全部 PID（自定义部署时可匹配多个 jar）。
     * <p>
     * 只按 jar 文件名匹配是不够的：生产与测试目录里可能放着同名的 jar，
     * 两个进程的命令行完全一样，无法区分（会显示同一个 PID，停止时还会一起被杀）。
     * 因此依次按下列依据收窄：
     * <ol>
     *   <li>进程工作目录在发布目录内（含 bin 等子目录；默认启动命令 {@code cd 发布目录 && java -jar ...} 即属此类）；</li>
     *   <li>命令行里出现 jar 的绝对路径（用 {@code -classpath}/{@code -jar} 加绝对路径启动时）。</li>
     * </ol>
     * 两条都不命中就返回空——宁可显示「未运行」，也不能猜：一旦把另一套环境的进程当成自己的，
     * 停止时就会误杀生产。确实无法靠进程信息识别的自定义命令，由 pid 文件兜底。
     * 同时排除本管理服务自身的进程与执行脚本的 shell。
     */
    private String pidListCommand(DeployTarget t) {
        String nameRe = ShellUtil.quote(jarRegex(t, false));
        String pathRe = ShellUtil.quote(jarRegex(t, true));
        String dir = ShellUtil.quote(ShellUtil.trimToEmpty(t.getRemoteDir()));
        StringBuilder sb = new StringBuilder();
        sb.append("DIR=").append(dir).append("; ");
        // 目录可能是软链，同时比较逻辑路径与物理路径
        sb.append("DIRP=$(cd \"$DIR\" 2>/dev/null && pwd -P); ");
        sb.append("CAND=$(pgrep -f ").append(nameRe).append(" 2>/dev/null); ");
        sb.append("OUT=''; ");
        sb.append("for q in $CAND; do ");
        // 排除执行脚本的 shell 自身（其命令行里就带着 jar 名）与本管理服务的进程
        sb.append("[ \"$q\" = \"$$\" ] && continue; ");
        sb.append("CMD=$(ps -o args= -p $q 2>/dev/null); ");
        sb.append("case \"$CMD\" in ''|*pgrep*|*deploy-manager*) continue;; esac; ");
        sb.append("CWD=$(readlink /proc/$q/cwd 2>/dev/null); ");
        // 进程工作目录在发布目录内（含 bin 等子目录）即认为是该环境的进程
        sb.append("IN=0; ");
        sb.append("if [ -n \"$DIR\" ]; then case \"$CWD/\" in \"$DIR/\"*) IN=1;; esac; fi; ");
        sb.append("if [ \"$IN\" = \"0\" ] && [ -n \"$DIRP\" ]; then case \"$CWD/\" in \"$DIRP/\"*) IN=1;; esac; fi; ");
        sb.append("if [ \"$IN\" = \"1\" ]; then OUT=\"$OUT $q\"; continue; fi; ");
        sb.append("if echo \"$CMD\" | grep -qE ").append(pathRe).append("; then OUT=\"$OUT $q\"; fi; ");
        sb.append("done; ");
        sb.append("echo $OUT");
        return sb.toString();
    }

    /**
     * 定位运行指定 jar 的进程 PID（多个时取第一个，用于展示与存活判断）。
     */
    private String pidCommand(DeployTarget t) {
        return "pids=$(" + pidListCommand(t) + "); echo $pids | awk '{print $1}'";
    }

    // ---------------------------------------------------------------- 工具

    /** 统一的命令执行入口：启用 su 时自动提权，未启用时普通执行 */
    private CommandResult exec(SshSession session, ServerConfig config, String script) {
        return session.execPrivileged(config, script);
    }

    private File saveUploadFile(ServerConfig config, DeployTarget target, MultipartFile file, String originalName) {
        try {
            String serverName = config.getName() == null ? "server"
                    : config.getName().replaceAll("[\\\\/:*?\"<>|\\s]", "_");
            String targetName = target.getName() == null ? "app"
                    : target.getName().replaceAll("[\\\\/:*?\"<>|\\s]", "_");
            File dir = new File(properties.getUploadDir(), serverName + "/" + targetName);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("创建本地上传目录失败: " + dir.getAbsolutePath());
            }
            String ts = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            File target2 = new File(dir, ts + "-" + new File(originalName).getName());
            file.transferTo(target2.getAbsoluteFile());
            return target2;
        } catch (Exception e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存上传文件并<b>保留原始文件名</b>：自定义部署的启动命令按文件名引用 jar，
     * 因此落到本服务时不能加时间戳前缀。每次发布使用独立批次目录，发布完成后整体清理。
     */
    private File saveUploadFileKeepName(ServerConfig config, DeployTarget target, MultipartFile file, String originalName) {
        try {
            String serverName = config.getName() == null ? "server"
                    : config.getName().replaceAll("[\\\\/:*?\"<>|\\s]", "_");
            String targetName = target.getName() == null ? "app"
                    : target.getName().replaceAll("[\\\\/:*?\"<>|\\s]", "_");
            String ts = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
            File dir = new File(properties.getUploadDir(), serverName + "/" + targetName + "/" + ts + "-batch");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("创建本地上传目录失败: " + dir.getAbsolutePath());
            }
            String safeName = new File(originalName == null ? "app.jar" : originalName).getName();
            File dest = new File(dir, safeName);
            file.transferTo(dest.getAbsoluteFile());
            return dest;
        } catch (Exception e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
    }

    private Map<String, String> parseKv(String output) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (output == null) {
            return map;
        }
        for (String line : output.split("\n")) {
            int i = line.indexOf('|');
            if (i > 0) {
                map.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        }
        return map;
    }

    private String firstLineWith(String output, String keyword) {
        if (output == null) {
            return "";
        }
        for (String line : output.split("\n")) {
            if (line.contains(keyword)) {
                return line.trim();
            }
        }
        return "";
    }

    private static String parentDir(String path) {
        if (path == null) {
            return "/tmp";
        }
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : "/";
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1fKB", Double.valueOf(kb));
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1fMB", Double.valueOf(mb));
        }
        return String.format("%.2fGB", Double.valueOf(mb / 1024.0));
    }

    /** 字节/秒 → 可读速度，如 1.2MB/s */
    private static String formatSpeed(long bps) {
        if (bps <= 0) {
            return "";
        }
        if (bps < 1024) {
            return bps + "B/s";
        }
        double kb = bps / 1024.0;
        if (kb < 1024) {
            return String.format("%.1fKB/s", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1fMB/s", mb);
        }
        return String.format("%.2fGB/s", mb / 1024.0);
    }

    private void closeQuietly(SshSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
