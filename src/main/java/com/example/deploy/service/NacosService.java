package com.example.deploy.service;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Nacos 集群一键部署：把本机上传的 nacos-server 安装包分发到所选的多台服务器（复用「服务器」列表勾选），
 * 解压后生成 {@code cluster.conf}（集群节点列表）与 {@code application.properties}
 * （外部数据源指向人大金仓 / Kingbase），并以集群模式启动各节点。
 *
 * <p>操作维度是「集群」：跨多台服务器协调，任务登记到共享 taskMap，进度通过现有的 SSE 端点订阅。</p>
 */
@Service
public class NacosService {

    private static final Logger log = LoggerFactory.getLogger(NacosService.class);

    private final DeployProperties properties;
    private final ServerConfigStore store;
    private final SshService sshService;
    private final OperationLogService operationLogService;
    private final DeployService deployService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public NacosService(DeployProperties properties, ServerConfigStore store, SshService sshService,
                        OperationLogService operationLogService, DeployService deployService) {
        this.properties = properties;
        this.store = store;
        this.sshService = sshService;
        this.operationLogService = operationLogService;
        this.deployService = deployService;
    }

    /** 部署入参（从控制器收集后传入，避免方法参数爆炸） */
    public static class NacosDeployOptions {
        public String clusterName;
        public int nacosPort;
        public String installDir;
        public String kbHost;
        public int kbPort;
        public String kbDb;
        public String kbUser;
        public String kbPassword;
        public String jdbcDriver;
        public String jdbcUrl;
        public String extraProps;
    }

    /**
     * 开始部署：校验文件与服务节点，落盘到本服务上传目录，创建集群任务后在后台线程执行。
     */
    public DeployTask deployAsync(List<String> nodeIds, MultipartFile file, String clusterName, int nacosPort,
                                  String installDir, String kbHost, int kbPort, String kbDb, String kbUser,
                                  String kbPassword, String jdbcDriver, String jdbcUrl, String extraProps) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的 nacos-server 安装包");
        }
        String originalName = file.getOriginalFilename() == null ? "nacos.tar.gz" : file.getOriginalFilename();
        if (!isNacosArchive(originalName)) {
            throw new IllegalArgumentException("只支持 tar.gz / tgz / tar / zip 格式的 Nacos 安装包");
        }
        if (nodeIds == null || nodeIds.size() < 2) {
            throw new IllegalArgumentException("Nacos 集群至少需要勾选 2 个节点服务器");
        }
        if (ShellUtil.isBlank(kbHost) || ShellUtil.isBlank(kbUser) || ShellUtil.isBlank(kbPassword)) {
            throw new IllegalArgumentException("请填写人大金仓数据库的连接信息（主机 / 账号 / 密码）");
        }
        if (ShellUtil.isBlank(installDir)) {
            installDir = "/opt/nacos";
        }
        installDir = installDir.trim();
        if (!installDir.startsWith("/")) {
            installDir = "/opt/" + installDir;
        }
        String name = (ShellUtil.isBlank(clusterName) ? "nacos-cluster" : clusterName.trim());

        List<ServerConfig> nodes = new ArrayList<ServerConfig>();
        for (String id : nodeIds) {
            nodes.add(store.getForConnect(id));
        }

        File local = saveUpload(originalName, file);
        NacosDeployOptions opt = new NacosDeployOptions();
        opt.clusterName = name;
        opt.nacosPort = nacosPort > 0 ? nacosPort : 8848;
        opt.installDir = installDir;
        opt.kbHost = kbHost.trim();
        opt.kbPort = kbPort > 0 ? kbPort : 54321;
        opt.kbDb = ShellUtil.isBlank(kbDb) ? "nacos_config" : kbDb.trim();
        opt.kbUser = kbUser.trim();
        opt.kbPassword = kbPassword;
        opt.jdbcDriver = ShellUtil.isBlank(jdbcDriver) ? "com.kingbase8.Driver" : jdbcDriver.trim();
        opt.jdbcUrl = jdbcUrl == null ? null : jdbcUrl.trim();
        opt.extraProps = extraProps;

        DeployTask task = deployService.createClusterTask("DEPLOY_NACOS", name);
        task.declareSteps("连接各节点", "上传安装包到各节点", "解压 Nacos 到各节点",
                "生成 cluster.conf（集群节点）", "生成 application.properties（人大金仓数据源）",
                "校验数据库可达（人大金仓）", "启动各节点", "健康检查");
        executor.submit(() -> doDeploy(task, nodes, local, opt));
        return task;
    }

    private void doDeploy(DeployTask task, List<ServerConfig> nodes, File localPkg, NacosDeployOptions opt) {
        Map<String, SshSession> sessions = new LinkedHashMap<String, SshSession>();
        Map<String, String> stagingByNode = new LinkedHashMap<String, String>();
        Map<String, String> homeByNode = new LinkedHashMap<String, String>();
        try {
            long begin = System.currentTimeMillis();
            // 0. 连接各节点
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            StringBuilder conD = new StringBuilder();
            boolean conOk = true;
            for (ServerConfig n : nodes) {
                try {
                    SshSession s = sshService.open(n);
                    sessions.put(n.getId(), s);
                    conD.append(n.getName()).append("(").append(n.getUsername()).append("@").append(n.getHost())
                            .append(":").append(n.effectivePort()).append(") ✓\n");
                } catch (Exception e) {
                    conOk = false;
                    conD.append(n.getName()).append("(").append(n.getHost()).append(") 连接失败: ")
                            .append(e.getMessage()).append("\n");
                }
            }
            stepDone(task, 0, conOk, conD.toString().trim(), t);
            if (!conOk) {
                finishTask(task, opt.clusterName, "DEPLOY_NACOS", begin, "部分节点连接失败，已中止", false);
                return;
            }

            // 1. 上传安装包到各节点
            t = System.currentTimeMillis();
            stepStart(task, 1);
            String ext = extension(localPkg.getName());
            int total = nodes.size();
            int idx = 0;
            boolean upOk = true;
            StringBuilder upD = new StringBuilder();
            for (ServerConfig n : nodes) {
                idx++;
                final int cur = idx;
                SshSession s = sessions.get(n.getId());
                String nacosStaging = s.stagingPath("nacos-install");
                String staging = nacosStaging + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
                s.mkdirs(nacosStaging);
                long fsize = localPkg.length();
                SftpProgressMonitor mon = nacosUploadMonitor(task, 1, fsize, n.getName(), cur, total);
                try {
                    s.uploadViaStaging(localPkg, staging, mon);
                    stagingByNode.put(n.getId(), staging);
                    upD.append(n.getName()).append(" ✓ ").append(readableSize(fsize)).append("\n");
                } catch (Exception e) {
                    upOk = false;
                    upD.append(n.getName()).append(" ✗ ").append(e.getMessage()).append("\n");
                }
            }
            stepDone(task, 1, upOk, upD.toString().trim(), t);
            if (!upOk) {
                finishTask(task, opt.clusterName, "DEPLOY_NACOS", begin, "部分节点上传失败，已中止", false);
                return;
            }

            // 2. 解压到各节点
            t = System.currentTimeMillis();
            stepStart(task, 2);
            boolean exOk = true;
            StringBuilder exD = new StringBuilder();
            for (ServerConfig n : nodes) {
                SshSession s = sessions.get(n.getId());
                String staging = stagingByNode.get(n.getId());
                String cmd = "mkdir -p " + ShellUtil.quote(opt.installDir) + "\n"
                        + "tar xf " + ShellUtil.quote(staging) + " -C " + ShellUtil.quote(opt.installDir) + "\n"
                        + "D=$(ls -1d " + ShellUtil.quote(opt.installDir) + "/*/ 2>/dev/null | head -1)\n"
                        + "if [ -z \"$D\" ]; then echo NO_NACOS_HOME; exit 1; fi\n"
                        + "D=$(echo \"$D\" | sed 's:/$::')\n"
                        + "if [ -f \"$D/bin/startup.sh\" ]; then echo NACOS_HOME=$D; else echo NO_NACOS_HOME; fi\n";
                CommandResult r = exec(s, n, cmd);
                String home = parsePrefix(r.getOutput(), "NACOS_HOME=");
                if (home == null) {
                    exOk = false;
                    exD.append(n.getName()).append(" ✗ 解压后未找到 bin/startup.sh（请确认是 nacos-server 包）\n");
                    continue;
                }
                homeByNode.put(n.getId(), home);
                exD.append(n.getName()).append(" → ").append(home).append("\n");
            }
            stepDone(task, 2, exOk, exD.toString().trim(), t);
            if (!exOk) {
                finishTask(task, opt.clusterName, "DEPLOY_NACOS", begin, "部分节点解压失败，已中止", false);
                return;
            }

            // 3. 生成 cluster.conf（所有节点 host:port）
            t = System.currentTimeMillis();
            stepStart(task, 3);
            StringBuilder clusterConf = new StringBuilder();
            clusterConf.append("# Generated by deploy-manager (Nacos 集群)\n");
            for (ServerConfig n : nodes) {
                clusterConf.append(n.getHost()).append(":").append(opt.nacosPort).append("\n");
            }
            boolean cfOk = true;
            StringBuilder cfD = new StringBuilder();
            for (ServerConfig n : nodes) {
                SshSession s = sessions.get(n.getId());
                String confPath = homeByNode.get(n.getId()) + "/conf/cluster.conf";
                try {
                    s.uploadContent(clusterConf.toString(), confPath);
                    cfD.append(n.getName()).append(" ✓ ").append(confPath).append("\n");
                } catch (Exception e) {
                    cfOk = false;
                    cfD.append(n.getName()).append(" ✗ ").append(e.getMessage()).append("\n");
                }
            }
            stepDone(task, 3, cfOk, cfD.toString().trim(), t);
            if (!cfOk) {
                finishTask(task, opt.clusterName, "DEPLOY_NACOS", begin, "cluster.conf 写入失败，已中止", false);
                return;
            }

            // 4. 生成 application.properties（外部数据源：人大金仓 / Kingbase）
            t = System.currentTimeMillis();
            stepStart(task, 4);
            String url = ShellUtil.isBlank(opt.jdbcUrl)
                    ? ("jdbc:kingbase8://" + opt.kbHost + ":" + opt.kbPort + "/" + opt.kbDb)
                    : opt.jdbcUrl;
            String block = "# >>> deploy-manager-nacos >>>\n"
                    + "spring.datasource.platform=postgresql\n"
                    + "db.num=1\n"
                    + "db.jdbcDriverName=" + opt.jdbcDriver + "\n"
                    + "db.url.0=" + url + "\n"
                    + "db.user.0=" + opt.kbUser + "\n"
                    + "db.password.0=" + opt.kbPassword + "\n"
                    + "server.port=" + opt.nacosPort + "\n";
            if (ShellUtil.isNotBlank(opt.extraProps)) {
                block += "\n# ---- 自定义追加配置 ----\n" + opt.extraProps.trim() + "\n";
            }
            block += "# <<< deploy-manager-nacos <<<\n";
            boolean apOk = true;
            StringBuilder apD = new StringBuilder();
            for (ServerConfig n : nodes) {
                SshSession s = sessions.get(n.getId());
                String confPath = homeByNode.get(n.getId()) + "/conf/application.properties";
                try {
                    String existing = exec(s, n, "cat " + ShellUtil.quote(confPath) + " 2>/dev/null || true").getOutput();
                    String base = stripBetween(existing, "# >>> deploy-manager-nacos >>>",
                            "# <<< deploy-manager-nacos <<<");
                    s.uploadContent(base + "\n" + block, confPath);
                    apD.append(n.getName()).append(" ✓ 数据源 → ").append(opt.jdbcDriver).append(" @ ")
                            .append(url).append("\n");
                } catch (Exception e) {
                    apOk = false;
                    apD.append(n.getName()).append(" ✗ ").append(e.getMessage()).append("\n");
                }
            }
            stepDone(task, 4, apOk, apD.toString().trim(), t);
            if (!apOk) {
                finishTask(task, opt.clusterName, "DEPLOY_NACOS", begin, "application.properties 写入失败，已中止", false);
                return;
            }

            // 5. 校验数据库可达（人大金仓）
            t = System.currentTimeMillis();
            stepStart(task, 5);
            ServerConfig first = nodes.get(0);
            CommandResult kb = exec(sessions.get(first.getId()), first,
                    "timeout 5 bash -c 'cat < /dev/null > /dev/tcp/" + ShellUtil.quote(opt.kbHost)
                            + "/" + opt.kbPort + "' && echo KB_REACHABLE || echo KB_UNREACHABLE");
            boolean kbOk = kb.getOutput().contains("KB_REACHABLE");
            String kbD = "数据库 " + opt.kbHost + ":" + opt.kbPort + "/" + opt.kbDb
                    + (kbOk ? " 从 Nacos 节点可达 ✓\n" : " 不可达（请检查网络 / 防火墙）\n")
                    + "注意：Nacos 需在数据库中已存在 nacos_config 库及元数据表；\n"
                    + "请将 Nacos 的 PostgreSQL 版初始化 SQL 在 Kingbase 中执行建表后集群方可正常启动。\n"
                    + "要求 nacos/plugins 下已放置 PostgreSQL 数据源插件与 kingbase8 JDBC 驱动。";
            stepDone(task, 5, kbOk, kbD.trim(), t);

            // 6. 启动各节点（集群模式）
            t = System.currentTimeMillis();
            stepStart(task, 6);
            boolean stOk = true;
            StringBuilder stD = new StringBuilder();
            for (ServerConfig n : nodes) {
                SshSession s = sessions.get(n.getId());
                String home = homeByNode.get(n.getId());
                String cmd = "export JAVA_HOME=${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java 2>/dev/null) 2>/dev/null) 2>/dev/null) 2>/dev/null)}\n"
                        + "cd " + ShellUtil.quote(home) + "\n"
                        + "bash bin/startup.sh -m cluster >/tmp/nacos-startup.log 2>&1\n"
                        + "echo START_EXIT=$?\n";
                CommandResult r = exec(s, n, cmd);
                if (r.getOutput().contains("START_EXIT=0")) {
                    stD.append(n.getName()).append(" ✓ 已触发启动（日志 /tmp/nacos-startup.log）\n");
                } else {
                    stOk = false;
                    stD.append(n.getName()).append(" ✗ 启动脚本异常：\n").append(ShellUtil.abbreviate(r.getOutput(), 400)).append("\n");
                }
            }
            stepDone(task, 6, stOk, stD.toString().trim() + "\n（集群模式下各节点需约 30~60s 完成选主与数据同步）", t);

            // 7. 健康检查（Nacos 启动后需 30~60s 选主同步，此处仅作连通性嗅探，不作为成败判定）
            t = System.currentTimeMillis();
            stepStart(task, 7);
            StringBuilder hD = new StringBuilder();
            for (ServerConfig n : nodes) {
                String ep = "http://" + n.getHost() + ":" + opt.nacosPort + "/nacos/v1/console/health/readiness";
                CommandResult r = exec(sessions.get(n.getId()), n,
                        "curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 " + ShellUtil.quote(ep)
                                + " 2>/dev/null || echo 000");
                String code = r.getOutput().trim();
                boolean up = "200".equals(code);
                if (up) {
                    hD.append(n.getName()).append(" ✓ 健康(").append(code).append(")\n");
                } else {
                    hD.append(n.getName()).append(" ⚠ 暂未就绪(").append(code)
                            .append(")，集群节点通常需 30~60s 完成选主，请稍后访问控制台或查看 /tmp/nacos-startup.log\n");
                }
            }
            stepDone(task, 7, true, hD.toString().trim(), t);
            finishTask(task, opt.clusterName, "DEPLOY_NACOS", begin,
                    "Nacos 集群已部署并启动，各节点日志见 /tmp/nacos-startup.log（控制台访问 http://<任意节点>:" + opt.nacosPort + "/nacos）", true);
        } catch (Exception e) {
            failTask(task, opt.clusterName, "DEPLOY_NACOS", e);
        } finally {
            for (SshSession s : sessions.values()) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            if (localPkg != null) {
                try {
                    localPkg.delete();
                } catch (Exception ignored) {
                    // ignore
                }
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

    private void stepDone(DeployTask task, int idx, boolean ok, String detail, long startTs) {
        StepStatus s = task.step(idx);
        s.status = ok ? "SUCCESS" : "FAIL";
        s.detail = detail;
        s.costMs = System.currentTimeMillis() - startTs;
        s.progress = 100;
        task.emitStep(s);
    }

    private void finishTask(DeployTask task, String title, String action, long begin, String message, boolean success) {
        DeployResult result = toResult(task, message, begin);
        operationLogService.record(title, title, action, success, result.getCostMs(), message);
        task.complete(result);
    }

    private void failTask(DeployTask task, String title, String action, Exception e) {
        log.error("Nacos 集群部署失败[{}]: {}", action, e.getMessage(), e);
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
        operationLogService.record(title, title, action, false, result.getCostMs(), msg);
        task.complete(result);
    }

    private DeployResult toResult(DeployTask task, String message, long begin) {
        DeployResult r = new DeployResult();
        boolean ok = true;
        for (StepStatus s : task.steps) {
            if (!"SUCCESS".equals(s.status)) {
                ok = false;
            }
        }
        r.setMessage(message);
        r.setSuccess(ok);
        r.setCostMs(System.currentTimeMillis() - begin);
        for (StepStatus s : task.steps) {
            r.step(s.name, "SUCCESS".equals(s.status), s.detail, s.costMs);
        }
        return r;
    }

    private SftpProgressMonitor nacosUploadMonitor(DeployTask task, int idx, long totalBytes,
                                                   String nodeName, int nodeIndex, int nodeCount) {
        return new SftpProgressMonitor() {
            private long sum = 0;
            private long start = System.currentTimeMillis();
            private long lastPush = 0;

            public void init(int op, String src, String dest, long max) {
                sum = 0;
                start = System.currentTimeMillis();
                lastPush = start;
                stepProgress(task, idx, (nodeIndex - 1) * 100 / nodeCount, 0,
                        "节点 " + nodeIndex + "/" + nodeCount + " " + nodeName + "：准备上传…");
            }

            public boolean count(long c) {
                sum += c;
                long now = System.currentTimeMillis();
                long elapsed = (now - start) / 1000;
                long bps = elapsed > 0 ? sum / elapsed : 0;
                int inner = totalBytes > 0 ? (int) Math.min(100, sum * 100 / totalBytes) : 0;
                int pct = (nodeIndex - 1) * 100 / nodeCount + inner / nodeCount;
                if (now - lastPush >= 300 || inner >= 100) {
                    lastPush = now;
                    stepProgress(task, idx, pct, bps,
                            "节点 " + nodeIndex + "/" + nodeCount + " " + nodeName + "：" + inner + "%（"
                                    + readableSize(sum) + " / " + readableSize(totalBytes) + "）");
                }
                return true;
            }

            public void end() {
                // no-op
            }
        };
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

    private CommandResult exec(SshSession session, ServerConfig config, String script) {
        return session.execPrivileged(config, script);
    }

    private File saveUpload(String originalName, MultipartFile file) {
        try {
            File dir = new File(properties.getUploadDir(), "nacos");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("创建 Nacos 上传目录失败: " + dir.getAbsolutePath());
            }
            String ts = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            File target = new File(dir, ts + "-" + new File(originalName).getName());
            file.transferTo(target.getAbsoluteFile());
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
    }

    private static boolean isNacosArchive(String name) {
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

    /** 删除 existing 中位于 begin/end 标记之间的旧块（含标记行），保留其余内容，便于幂等重部署 */
    private static String stripBetween(String existing, String begin, String end) {
        if (existing == null) {
            return "";
        }
        String[] lines = existing.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inBlock = false;
        for (String line : lines) {
            if (line.contains(begin)) {
                inBlock = true;
                continue;
            }
            if (line.contains(end)) {
                inBlock = false;
                continue;
            }
            if (!inBlock) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
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
}
