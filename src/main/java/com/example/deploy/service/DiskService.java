package com.example.deploy.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.deploy.model.*;
import com.example.deploy.ssh.SshService;
import com.example.deploy.store.ServerConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.deploy.ssh.SshSession;
import com.example.deploy.util.ShellUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 磁盘管理：扫描服务器磁盘（fdisk -l + lsblk），并对全新未格式化磁盘执行整盘格式化挂载。
 *
 * <p>安全约束（需求确认）：仅对「未挂载、无文件系统、非系统盘」的全新磁盘执行格式化挂载；
 * 挂载前会二次校验（已挂载 / 已有文件系统则跳过，绝不误伤已有数据）。默认 ext4，并写入 /etc/fstab。</p>
 */
@Service
public class DiskService {

    private static final Logger log = LoggerFactory.getLogger(DiskService.class);

    private final ServerConfigStore store;
    private final SshService sshService;
    private final OperationLogService opLog;
    private final DeployService deployService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** 扫描结果缓存：serverId -> (rawFdisk, disks)，避免重复扫描；仅用于展示，挂载前以实时校验为准 */
    private final ConcurrentHashMap<String, List<DiskInfo>> scanCache = new ConcurrentHashMap<String, List<DiskInfo>>();

    public DiskService(ServerConfigStore store, SshService sshService,
                       OperationLogService opLog, DeployService deployService) {
        this.store = store;
        this.sshService = sshService;
        this.opLog = opLog;
        this.deployService = deployService;
    }

    /** 扫描结果（含原始 fdisk -l 文本，便于前端展示） */
    public static class DiskScanResult {
        public String rawFdisk;
        public List<DiskInfo> disks;
    }

    /** 同步扫描：解析 fdisk -l 与 lsblk，返回可挂载的新磁盘列表 */
    public DiskScanResult scan(String serverId) {
        ServerConfig config = store.getForConnect(serverId);
        if (config == null) {
            throw new IllegalArgumentException("服务器不存在");
        }
        long begin = System.currentTimeMillis();
        SshSession session = null;
        try {
            session = sshService.open(config);
            CommandResult fdisk = exec(session, config, "fdisk -l 2>/dev/null; true");
            CommandResult lsblk = exec(session, config,
                    "lsblk -b -J -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT,UUID,MODEL 2>/dev/null; true");

            Map<String, JsonNode> lsMap = parseLsblk(lsblk.getOutput());
            List<DiskInfo> disks = buildDiskList(fdisk.getOutput(), lsMap);

            scanCache.put(serverId, disks);
            DiskScanResult r = new DiskScanResult();
            r.rawFdisk = fdisk.getOutput() == null ? "" : fdisk.getOutput().trim();
            r.disks = disks;
            opLog.record(config, "DISK_SCAN", true, System.currentTimeMillis() - begin,
                    "扫描到 " + disks.size() + " 块磁盘，其中 " + countMountable(disks) + " 块可挂载");
            return r;
        } catch (Exception e) {
            opLog.record(config, "DISK_SCAN", false, System.currentTimeMillis() - begin, e.getMessage());
            throw new IllegalStateException("扫描磁盘失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(session);
        }
    }

    /** 异步挂载：返回已登记到共享 taskMap 的 DeployTask（可被 SSE 端点订阅） */
    public DeployTask mountAsync(DiskMountRequest req) {
        if (req == null || req.getDisks() == null || req.getDisks().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一块磁盘");
        }
        String fs = ShellUtil.isBlank(req.getFsType()) ? "ext4" : req.getFsType().trim().toLowerCase();
        if (!"ext4".equals(fs) && !"xfs".equals(fs)) {
            throw new IllegalArgumentException("仅支持 ext4 / xfs 文件系统，收到: " + req.getFsType());
        }
        ServerConfig config = store.getForConnect(req.getServerId());
        if (config == null) {
            throw new IllegalArgumentException("服务器不存在");
        }
        DeployTask task = deployService.createServerTask(config, "MOUNT_DISK");
        List<String> stepNames = new ArrayList<String>();
        stepNames.add("连接服务器");
        for (DiskMountRequest.DiskItem d : req.getDisks()) {
            stepNames.add("挂载 " + d.device + " → " + d.mountPoint);
        }
        stepNames.add("验证挂载");
        task.declareSteps(stepNames.toArray(new String[0]));

        final String fsFinal = fs;
        final ServerConfig cfg = config;
        executor.submit(() -> doMount(task, cfg, req, fsFinal));
        return task;
    }

    // ------------------------------------------------------------ 挂载执行体

    private void doMount(DeployTask task, ServerConfig config, DiskMountRequest req, String fs) {
        SshSession session = null;
        long begin = System.currentTimeMillis();
        try {
            long t = System.currentTimeMillis();
            stepStart(task, 0);
            log.info("[磁盘] 连接服务器 {}@{}:{}", config.getUsername(), config.getHost(), config.effectivePort());
            session = sshService.open(config);
            stepDone(task, 0, true, config.getUsername() + "@" + config.getHost() + ":" + config.effectivePort(), t);

            int idx = 1;
            for (DiskMountRequest.DiskItem d : req.getDisks()) {
                t = System.currentTimeMillis();
                stepStart(task, idx);
                String device = ShellUtil.quote(d.device);
                String mp = ShellUtil.quote(d.mountPoint);
                String addFstab = req.isAddFstab() ? "1" : "0";
                String script = buildMountScript(device, mp, fs, addFstab);
                CommandResult r = exec(session, config, script);
                boolean mountOk = interpretMountResult(task, idx, r, d.device, d.mountPoint, fs);
                stepDone(task, idx, mountOk, task.step(idx).detail, t);
                idx++;
            }

            // 验证挂载
            t = System.currentTimeMillis();
            stepStart(task, idx);
            StringBuilder sb = new StringBuilder();
            boolean allOk = true;
            for (DiskMountRequest.DiskItem d : req.getDisks()) {
                CommandResult v = exec(session, config, "findmnt -n -o TARGET " + ShellUtil.quote(d.device) + " 2>/dev/null || echo NONE");
                String target = v.getOutput() == null ? "" : v.getOutput().trim();
                if (target.isEmpty() || "NONE".equals(target)) {
                    allOk = false;
                    sb.append(d.device).append(" 未挂载成功\n");
                } else {
                    sb.append(d.device).append(" → ").append(target).append(" ✓\n");
                }
            }
            stepDone(task, idx, allOk, allOk ? sb.toString().trim() : sb.toString().trim(), t);

            boolean ok = taskAllOk(task);
            DeployResult result = toResult(task, ok ? "磁盘挂载完成" : "部分磁盘挂载失败", begin);
            opLog.record(config, "MOUNT_DISK", ok, System.currentTimeMillis() - begin, result.getMessage());
            task.complete(result);
        } catch (Exception e) {
            failTask(task, config, "MOUNT_DISK", e, begin);
        } finally {
            closeQuietly(session);
        }
    }

    private String buildMountScript(String device, String mp, String fs, String addFstab) {
        // device/mp 已是经 ShellUtil.quote 包裹的字符串，脚本内作为变量引用
        String mkfs = "ext4".equals(fs)
                ? "mkfs.ext4 -F " + device
                : "mkfs.xfs -f " + device;
        StringBuilder sb = new StringBuilder();
        sb.append("set -e\n");
        // 安全二次校验：已挂载 / 已有文件系统 => 跳过，绝不格式化已有数据
        sb.append("if findmnt -n -o SOURCE ").append(device).append(" >/dev/null 2>&1; then echo ALREADY_MOUNTED; exit 0; fi\n");
        sb.append("if blkid ").append(device).append(" >/dev/null 2>&1; then echo HAS_FS; exit 0; fi\n");
        // 整盘格式化（不分区）
        sb.append(mkfs).append("\n");
        // 创建挂载点并挂载
        sb.append("mkdir -p ").append(mp).append("\n");
        sb.append("mount ").append(device).append(' ').append(mp).append("\n");
        // 写入 /etc/fstab（用 UUID 更稳健；获取不到则用设备名）
        sb.append("if [ \"").append(addFstab).append("\" = \"1\" ]; then\n");
        sb.append("  UUID=$(blkid -s UUID -o value ").append(device).append(" 2>/dev/null || true)\n");
        sb.append("  if [ -n \"$UUID\" ]; then ENTRY=\"UUID=$UUID ").append(mp).append(' ').append(fs).append(" defaults 0 0\";\n");
        sb.append("  else ENTRY=\"").append(device).append(' ').append(mp).append(' ').append(fs).append(" defaults 0 0\"; fi\n");
        sb.append("  if ! grep -qF \"$ENTRY\" /etc/fstab; then echo \"$ENTRY\" >> /etc/fstab; fi\n");
        sb.append("  mount -a || echo FSTAB_WARN\n");
        sb.append("fi\n");
        sb.append("echo MOUNT_DONE\n");
        return sb.toString();
    }

    /** 解析挂载命令输出，写入步骤 detail 并返回是否成功 */
    private boolean interpretMountResult(DeployTask task, int idx, CommandResult r,
                                         String device, String mp, String fs) {
        String out = r.getOutput() == null ? "" : r.getOutput();
        if (out.contains("ALREADY_MOUNTED")) {
            task.step(idx).detail = device + " 已挂载，跳过格式化（安全保护）";
            return true;
        }
        if (out.contains("HAS_FS")) {
            task.step(idx).detail = device + " 已有文件系统，跳过格式化（安全保护）";
            return true;
        }
        if (out.contains("MOUNT_DONE")) {
            String detail = "已格式化为 " + fs + " 并挂载到 " + mp;
            if (out.contains("FSTAB_WARN")) {
                detail += "（提示：/etc/fstab 校验未完全通过，请检查）";
            } else if (r.getExitCode() == 0) {
                detail += "，已写入 /etc/fstab";
            }
            task.step(idx).detail = detail;
            return true;
        }
        // mkfs/mount 失败：脚本因 set -e 中途退出，exitCode != 0
        task.step(idx).detail = "格式化/挂载失败：" + ShellUtil.abbreviate(
                (out + "\n" + (r.getStderr() == null ? "" : r.getStderr())).trim(), 1500);
        return false;
    }

    // ------------------------------------------------------------ 扫描解析

    private Map<String, JsonNode> parseLsblk(String json) {
        Map<String, JsonNode> map = new LinkedHashMap<String, JsonNode>();
        if (json == null || json.trim().isEmpty()) {
            return map;
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode arr = root.get("blockdevices");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    collectNodes(n, map);
                }
            }
        } catch (Exception e) {
            log.warn("[磁盘] 解析 lsblk 输出失败: {}", e.getMessage());
        }
        return map;
    }

    private void collectNodes(JsonNode node, Map<String, JsonNode> map) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode name = node.get("name");
        if (name != null) {
            map.put(name.asText(), node);
        }
        JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode c : children) {
                collectNodes(c, map);
            }
        }
    }

    private List<DiskInfo> buildDiskList(String fdiskOut, Map<String, JsonNode> lsMap) {
        List<DiskInfo> result = new ArrayList<DiskInfo>();
        // 1) 优先从 fdisk -l 的 "Disk /dev/xxx:" 行枚举设备（即「fdisk -l 会出现的磁盘」）
        List<String> devices = new ArrayList<String>();
        if (fdiskOut != null) {
            for (String line : fdiskOut.split("\n")) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^Disk (/dev/\\S+):").matcher(line);
                if (m.find()) {
                    String dev = m.group(1);
                    if (!devices.contains(dev)) {
                        devices.add(dev);
                    }
                }
            }
        }
        // 2) fdisk 不可用 / 无输出时，回退到 lsblk 顶层磁盘
        if (devices.isEmpty()) {
            for (Map.Entry<String, JsonNode> e : lsMap.entrySet()) {
                JsonNode n = e.getValue();
                if (n.has("type") && "disk".equals(n.get("type").asText())) {
                    String dev = "/dev/" + e.getKey();
                    if (!devices.contains(dev)) {
                        devices.add(dev);
                    }
                }
            }
        }
        for (String dev : devices) {
            JsonNode node = lsMap.get(dev.replace("/dev/", ""));
            result.add(toDiskInfo(dev, node));
        }
        return result;
    }

    private DiskInfo toDiskInfo(String device, JsonNode node) {
        DiskInfo d = new DiskInfo();
        d.setDevice(device);
        if (node == null) {
            d.setMountable(false);
            d.setReason("无法获取磁盘详情（可能需 root 权限，请开启 su 切换）");
            return d;
        }
        long size = node.has("size") && node.get("size").isNumber() ? node.get("size").asLong() : 0;
        d.setSizeBytes(size);
        d.setSizeHuman(humanSize(size));
        d.setModel(node.has("model") && !node.get("model").isNull() ? node.get("model").asText() : "");
        d.setUuid(node.has("uuid") && !node.get("uuid").isNull() ? node.get("uuid").asText() : "");
        d.setFstype(node.has("fstype") && !node.get("fstype").isNull() ? node.get("fstype").asText() : "");

        // 遍历整棵子树，收集挂载点与文件系统情况
        ScanState st = new ScanState();
        walk(node, st);

        d.setMountpoint(st.mountpoints.isEmpty() ? "" : String.join(", ", st.mountpoints));

        if (st.hasRoot) {
            d.setMountable(false);
            d.setReason("系统盘（含根分区 /），不可操作");
        } else if (st.hasMount) {
            d.setMountable(false);
            d.setReason("已挂载（" + String.join(", ", st.mountpoints) + "），不可操作");
        } else if (st.hasFs) {
            d.setMountable(false);
            d.setReason("已有文件系统（" + st.fstypes + "），不可操作");
        } else if (size <= 0) {
            d.setMountable(false);
            d.setReason("无法识别容量");
        } else {
            d.setMountable(true);
            d.setReason("全新未格式化磁盘，可整盘格式化挂载");
        }
        return d;
    }

    private static class ScanState {
        boolean hasMount = false;
        boolean hasFs = false;
        boolean hasRoot = false;
        List<String> mountpoints = new ArrayList<String>();
        String fstypes = "";
    }

    private void walk(JsonNode node, ScanState st) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode mp = node.get("mountpoint");
        if (mp != null && !mp.isNull() && !mp.asText().trim().isEmpty()) {
            String m = mp.asText().trim();
            st.hasMount = true;
            st.mountpoints.add(m);
            if ("/".equals(m)) {
                st.hasRoot = true;
            }
        }
        JsonNode fs = node.get("fstype");
        if (fs != null && !fs.isNull() && !fs.asText().trim().isEmpty()) {
            st.hasFs = true;
            st.fstypes = st.fstypes.isEmpty() ? fs.asText() : st.fstypes + "," + fs.asText();
        }
        JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode c : children) {
                walk(c, st);
            }
        }
    }

    private static int countMountable(List<DiskInfo> disks) {
        int n = 0;
        for (DiskInfo d : disks) {
            if (d.isMountable()) {
                n++;
            }
        }
        return n;
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "-";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1fKB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1fMB", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format("%.1fGB", gb);
        }
        return String.format("%.2fTB", gb / 1024.0);
    }

    // ------------------------------------------------------------ 任务辅助

    private void stepStart(DeployTask task, int idx) {
        task.step(idx).status = "RUNNING";
        task.step(idx).progress = 0;
        task.emitStep(task.step(idx));
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

    private DeployResult toResult(DeployTask task, String message, long begin) {
        DeployResult r = new DeployResult();
        r.setMessage(message);
        r.setSuccess(taskAllOk(task));
        r.setCostMs(System.currentTimeMillis() - begin);
        for (StepStatus s : task.steps) {
            r.step(s.name, "SUCCESS".equals(s.status), s.detail, s.costMs);
        }
        return r;
    }

    private void failTask(DeployTask task, ServerConfig config, String action, Exception e, long begin) {
        log.error("[磁盘] 任务失败[{}]: {}", action, e.getMessage(), e);
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
        DeployResult result = toResult(task, msg, begin);
        opLog.record(config, action, false, System.currentTimeMillis() - begin, msg);
        task.complete(result);
    }

    private CommandResult exec(SshSession session, ServerConfig config, String script) {
        return session.execPrivileged(config, script);
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
