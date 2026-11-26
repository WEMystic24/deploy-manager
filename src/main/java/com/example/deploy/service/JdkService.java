package com.example.deploy.service;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.deploy.config.DeployProperties;
import com.example.deploy.model.CommandResult;
import com.example.deploy.model.DeployResult;
import com.example.deploy.model.DeployTask;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.model.StepStatus;
import com.example.deploy.ssh.SshService;
import com.example.deploy.ssh.SshSession;
import com.example.deploy.store.ServerConfigStore;
import com.example.deploy.util.ShellUtil;
import com.jcraft.jsch.SftpProgressMonitor;

/**
 * 一键部署 JDK：把本服务上传的 JDK 压缩包（tar.gz / tgz / tar / zip）通过 SFTP 推送到目标服务器，
 * 解压到指定安装目录，并写入 {@code /etc/profile.d/deploy-jdk.sh} 配置 JAVA_HOME 与 PATH。
 *
 * <p>操作维度是「服务器」：一个服务器上只装一份 JDK，与发布配置（DeployTarget）无关。
 * 任务复用 {@link DeployService#createServerTask} 登记到共享 taskMap，进度通过现有的 SSE 端点订阅。</p>
 */
@Service
public class JdkService {

    private static final Logger log = LoggerFactory.getLogger(JdkService.class);

    private final DeployProperties properties;
    private final ServerConfigStore store;
    private final SshService sshService;
    private final OperationLogService operationLogService;
    private final DeployService deployService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public JdkService(DeployProperties properties, ServerConfigStore store, SshService sshService,
                      OperationLogService operationLogService, DeployService deployService) {
        this.properties = properties;
        this.store = store;
        this.sshService = sshService;
        this.operationLogService = operationLogService;
        this.deployService = deployService;
    }

    /**
     * 开始安装：校验文件类型，落盘到本服务上传目录，创建任务后在后台线程执行。
     *
     * @param serverId   目标服务器 ID
     * @param file      本机选中的 JDK 压缩包
     * @param installDir 安装目录（JDK 将被解压到此目录下），为空默认 /usr/local/java
     * @param linkName   可选：在 installDir 下创建的软链接名（如 jdk8），便于多版本切换；填了就用该链接作为 JAVA_HOME
     */
    public DeployTask installAsync(String serverId, MultipartFile file, String installDir, String linkName) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的 JDK 安装包");
        }
        String originalName = file.getOriginalFilename() == null ? "jdk.tar.gz" : file.getOriginalFilename();
        if (!isJdkArchive(originalName)) {
            throw new IllegalArgumentException("只支持 tar.gz / tgz / tar / zip 格式的 JDK 安装包");
        }
        if (ShellUtil.isBlank(installDir)) {
            installDir = "/usr/local/java";
        }
        installDir = installDir.trim();
        if (!installDir.startsWith("/")) {
            installDir = "/usr/local/" + installDir;
        }
        final String dir = installDir;
        final String link = (linkName == null ? "" : linkName.trim()).replaceAll("[^A-Za-z0-9._-]", "");
        ServerConfig config = store.getForConnect(serverId);
        File local = saveUpload(config, file, originalName);
        DeployTask task = deployService.createServerTask(config, "INSTALL_JDK");
        task.declareSteps("连接服务器", "上传 JDK 安装包", "解压安装", "配置环境变量", "验证 JDK");
        executor.submit(() -> doInstall(task, config, local, dir, link));
        return task;
    }

    private void doInstall(DeployTask task, ServerConfig config, File localFile, String installDir, String linkName) {
        SshSession session = null;
        try {
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            log.info("[JDK] 连接服务器 {}@{}:{}", config.getUsername(), config.getHost(), config.effectivePort());
            session = sshService.open(config);
            stepDone(task, 0, true, config.getUsername() + "@" + config.getHost() + ":" + config.effectivePort(), t);

            // 1. 上传压缩包到目标服务器中转目录（默认 /tmp，可通过 deploy.staging-dir 改到非 /tmp）
            t = System.currentTimeMillis();
            stepStart(task, 1);
            String ext = extension(localFile.getName());
            String jdkStaging = session.stagingPath("jdk-install");
            String staging = jdkStaging + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
            session.mkdirs(jdkStaging);
            final long totalBytes = localFile.length();
            SftpProgressMonitor mon = uploadMonitor(task, 1, totalBytes);
            session.uploadViaStaging(localFile, staging, mon);
            stepDone(task, 1, true, "已上传 " + readableSize(totalBytes) + " → " + staging, t);

            // 2. 解压并按 bin/java 探测 JAVA_HOME
            t = System.currentTimeMillis();
            stepStart(task, 2);
            CommandResult mk = exec(session, config, "mkdir -p " + ShellUtil.quote(installDir));
            if (!mk.isSuccess()) {
                stepDone(task, 2, false, mk.getOutput(), t);
                finishTask(task, config, "INSTALL_JDK", task.startTime, "创建安装目录失败", false);
                return;
            }
            boolean isZip = "zip".equals(ext);
            String extractCmd = isZip
                    ? "unzip -o " + ShellUtil.quote(staging) + " -d " + ShellUtil.quote(installDir)
                    : "tar xf " + ShellUtil.quote(staging) + " -C " + ShellUtil.quote(installDir);
            CommandResult ex = exec(session, config, extractCmd);
            if (!ex.isSuccess()) {
                String hint = isZip && ex.getOutput().toLowerCase().contains("not found")
                        ? "解压失败：远端缺少 unzip 命令，请改用 .tar.gz 格式的 JDK 安装包"
                        : ex.getOutput();
                stepDone(task, 2, false, hint, t);
                finishTask(task, config, "INSTALL_JDK", task.startTime, "解压失败", false);
                return;
            }
            // 探测安装目录下的 java 可执行文件，兼容「直接解压出 bin/java」与「解压出 xxx/bin/java」两种结构
            String detect = "JBIN=$(ls -1d " + ShellUtil.quote(installDir) + "/bin/java "
                    + ShellUtil.quote(installDir) + "/*/bin/java 2>/dev/null | head -1); "
                    + "if [ -n \"$JBIN\" ]; then JH=$(dirname \"$JBIN\"); JH=$(dirname \"$JH\"); echo \"JAVAHOME=$JH\"; "
                    + "else echo NO_JAVA_FOUND; fi";
            CommandResult detectR = exec(session, config, detect);
            String javaHome = parsePrefix(detectR.getOutput(), "JAVAHOME=");
            if (javaHome == null) {
                stepDone(task, 2, false,
                        "未在 " + installDir + " 下找到 bin/java，请确认上传的是完整 JDK（而非 JRE 或错误文件）", t);
                finishTask(task, config, "INSTALL_JDK", task.startTime, "未找到可用的 JDK", false);
                return;
            }
            if (linkName.length() > 0) {
                String linkPath = installDir + "/" + linkName;
                exec(session, config, "ln -sfn " + ShellUtil.quote(javaHome) + " " + ShellUtil.quote(linkPath));
                javaHome = linkPath;
            }
            CommandResult ver = exec(session, config, ShellUtil.quote(javaHome) + "/bin/java -version 2>&1");
            stepDone(task, 2, true, "JAVA_HOME=" + javaHome + "\n" + ShellUtil.abbreviate(ver.getOutput(), 400), t);

            // 3. 写入 /etc/profile.d 配置 JAVA_HOME 与 PATH
            t = System.currentTimeMillis();
            stepStart(task, 3);
            String profile = "/etc/profile.d/deploy-jdk.sh";
            String content = "# Generated by deploy-manager (INSTALL_JDK)\n"
                    + "export JAVA_HOME=" + javaHome + "\n"
                    + "export PATH=$JAVA_HOME/bin:$PATH\n";
            String writeCmd = "cat > " + profile + " <<'DEPLOY_JDK_EOF' && chmod 644 " + profile + " && echo PROFILE_OK\n"
                    + content
                    + "DEPLOY_JDK_EOF\n";
            CommandResult wr = exec(session, config, writeCmd);
            boolean ok3 = wr.isSuccess() && wr.getOutput().contains("PROFILE_OK");
            if (!ok3) {
                String hint = ShellUtil.isNotBlank(wr.getOutput()) ? wr.getOutput().trim()
                        : "写入 " + profile + " 失败（可能需要 root 权限，请开启「登录后 su 切换到 root」）";
                stepDone(task, 3, false, hint, t);
                finishTask(task, config, "INSTALL_JDK", task.startTime, "环境变量配置失败", false);
                return;
            }
            stepDone(task, 3, true, "已写入 " + profile + "（JAVA_HOME=" + javaHome + "）", t);

            // 4. 验证：新开 login shell 执行 java -version，确认 PATH 已生效
            t = System.currentTimeMillis();
            stepStart(task, 4);
            CommandResult v = exec(session, config, "java -version 2>&1; echo '---'; echo JAVA_HOME=$JAVA_HOME");
            boolean ok4 = v.getOutput().contains("version");
            String detail = ok4 ? v.getOutput()
                    : "java -version 未能执行（profile.d 需重新登录生效，可直接用 $JAVA_HOME/bin/java 验证）；\n" + v.getOutput();
            stepDone(task, 4, true, detail, t);
            finishTask(task, config, "INSTALL_JDK", task.startTime,
                    ok4 ? "JDK 安装完成，java/javac 已可用" : "JDK 已安装，但全局验证未通过（请重新登录后生效）", ok4);
        } catch (Exception e) {
            failTask(task, config, e);
        } finally {
            closeQuietly(session);
            try {
                localFile.delete();
            } catch (Exception ignored) {
                // 清理失败不影响主流程
            }
        }
    }

    // ---------------------------------------------------------------- 步骤辅助

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

    private void finishTask(DeployTask task, ServerConfig config, String action, long begin, String message, boolean success) {
        DeployResult result = toResult(task, message, begin);
        operationLogService.record(config.getId(), config.getName(), action, success, result.getCostMs(), message);
        task.complete(result);
    }

    private void failTask(DeployTask task, ServerConfig config, Exception e) {
        log.error("JDK 安装失败[{}]: {}", task.action, e.getMessage(), e);
        String msg = e.getMessage();
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
        DeployResult result = toResult(task, msg, task.startTime);
        operationLogService.record(config.getId(), config.getName(), "INSTALL_JDK", false, result.getCostMs(), msg);
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

    // ---------------------------------------------------------------- 工具

    private SftpProgressMonitor uploadMonitor(DeployTask task, int idx, long totalBytes) {
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

    private CommandResult exec(SshSession session, ServerConfig config, String script) {
        return session.execPrivileged(config, script);
    }

    private File saveUpload(ServerConfig config, MultipartFile file, String originalName) {
        try {
            String serverName = config.getName() == null ? "server"
                    : config.getName().replaceAll("[\\\\/:*?\"<>|\\s]", "_");
            File dir = new File(properties.getUploadDir(), "jdk/" + serverName);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("创建 JDK 上传目录失败: " + dir.getAbsolutePath());
            }
            String ts = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            File target = new File(dir, ts + "-" + new File(originalName).getName());
            file.transferTo(target.getAbsoluteFile());
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
    }

    private static boolean isJdkArchive(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".tar") || n.endsWith(".zip");
    }

    private static String extension(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".tar.gz")) {
            return "tar.gz";
        }
        if (n.endsWith(".tgz")) {
            return "tgz";
        }
        if (n.endsWith(".tar")) {
            return "tar";
        }
        if (n.endsWith(".zip")) {
            return "zip";
        }
        return "bin";
    }

    private static String parsePrefix(String output, String prefix) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
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
