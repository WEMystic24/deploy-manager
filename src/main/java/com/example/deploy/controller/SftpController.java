package com.example.deploy.controller;

import java.io.File;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.deploy.model.CommandResult;
import com.example.deploy.model.R;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.model.SftpDir;
import com.example.deploy.service.OperationLogService;
import com.example.deploy.ssh.SshService;
import com.example.deploy.ssh.SshSession;
import com.example.deploy.store.ServerConfigStore;
import com.example.deploy.util.ShellUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SFTP 文件浏览 / 上传 / 建目录接口。
 *
 * <p>列目录走 exec（启用 su 时自动提权，/opt 等仅 root 可见的目录也能列出）；
 * 上传先 SFTP 到 /tmp 中转再以 root cp 到目标，兼容需 su 的场景；
 * 文件从浏览器经本服务转发到远程，前端通过 XHR 的 upload.onprogress 展示上传百分比。</p>
 */
@RestController
@RequestMapping("/api/servers/{id}/sftp")
public class SftpController {

    private final ServerConfigStore store;
    private final SshService sshService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    public SftpController(ServerConfigStore store, SshService sshService,
                         OperationLogService operationLogService, ObjectMapper objectMapper) {
        this.store = store;
        this.sshService = sshService;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper;
    }

    /** 列出远程目录内容；path 为空则列当前用户家目录 */
    @GetMapping("/list")
    public R<SftpDir> list(@PathVariable("id") String id,
                           @RequestParam(value = "path", required = false) String path) {
        ServerConfig cfg = store.getForConnect(id);
        long begin = System.currentTimeMillis();
        try (SshSession s = sshService.open(cfg)) {
            SftpDir dir = s.listRemote(path);
            operationLogService.record(cfg, "SFTP_LIST", true, System.currentTimeMillis() - begin,
                    "路径 " + (ShellUtil.isBlank(path) ? "home" : path));
            return R.ok(dir);
        } catch (Exception e) {
            operationLogService.record(cfg, "SFTP_LIST", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /** 上传文件到远程目录（浏览器→本服务→远程，前端带上传进度） */
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@PathVariable("id") String id,
                                         @RequestParam("file") MultipartFile file,
                                         @RequestParam("path") String path) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String originalName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        originalName = new File(originalName).getName();
        if (ShellUtil.isBlank(path)) {
            throw new IllegalArgumentException("请先选择上传目标目录");
        }
        ServerConfig cfg = store.getForConnect(id);
        File local = saveTemp(file, originalName);
        long begin = System.currentTimeMillis();
        try (SshSession s = sshService.open(cfg)) {
            String base = path.trim().replaceAll("/+$", "");
            String remotePath = base + "/" + originalName;
            s.uploadViaStaging(local, remotePath, null);
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            res.put("name", originalName);
            res.put("remotePath", remotePath);
            res.put("size", local.length());
            operationLogService.record(cfg, "SFTP_UPLOAD", true, System.currentTimeMillis() - begin,
                    originalName + " → " + remotePath + " (" + local.length() + "B)");
            return R.ok(res);
        } catch (Exception e) {
            operationLogService.record(cfg, "SFTP_UPLOAD", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /** 在远程创建目录（path 为完整绝对路径） */
    @PostMapping("/mkdir")
    public R<Map<String, Object>> mkdir(@PathVariable("id") String id,
                                       @RequestParam("path") String path) {
        if (ShellUtil.isBlank(path) || !path.trim().startsWith("/")) {
            throw new IllegalArgumentException("目录路径必须是绝对路径，例如 /opt/app/data");
        }
        ServerConfig cfg = store.getForConnect(id);
        long begin = System.currentTimeMillis();
        try (SshSession s = sshService.open(cfg)) {
            CommandResult r = s.execPrivileged(s.getConfig(), "mkdir -p " + ShellUtil.quote(path));
            if (!r.isSuccess()) {
                throw new IllegalStateException("创建目录失败: " + ShellUtil.abbreviate(r.getOutput(), 300));
            }
            operationLogService.record(cfg, "SFTP_MKDIR", true, System.currentTimeMillis() - begin, "路径 " + path);
        } catch (Exception e) {
            operationLogService.record(cfg, "SFTP_MKDIR", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("path", path);
        return R.ok(res);
    }

    /** 删除远程文件或目录（递归）。禁止删除根目录与空路径，避免误操作。 */
    @PostMapping("/delete")
    public R<Map<String, Object>> delete(@PathVariable("id") String id,
                                        @RequestParam("path") String path) {
        if (ShellUtil.isBlank(path) || !path.trim().startsWith("/") || path.trim().equals("/")) {
            throw new IllegalArgumentException("只能删除绝对路径且不能是根目录");
        }
        ServerConfig cfg = store.getForConnect(id);
        long begin = System.currentTimeMillis();
        try (SshSession s = sshService.open(cfg)) {
            CommandResult r = s.execPrivileged(s.getConfig(), "rm -rf " + ShellUtil.quote(path.trim()));
            if (!r.isSuccess()) {
                throw new IllegalStateException("删除失败: " + ShellUtil.abbreviate(r.getOutput(), 300));
            }
            operationLogService.record(cfg, "SFTP_DELETE", true, System.currentTimeMillis() - begin, "路径 " + path);
        } catch (Exception e) {
            operationLogService.record(cfg, "SFTP_DELETE", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("path", path);
        return R.ok(res);
    }

    /**
     * 下载远程文件（浏览器直接下载）。
     * 先以 exec（启用 su 时为 root）把文件 cp 到 /tmp 中转并 chmod +r，
     * 再以 SFTP 流式回传浏览器，避免 root-only 文件因登录账号无读权限而失败。
     * 所有异常在方法内捕获：响应未提交时返回 JSON 错误（HTTP 500），便于前端提示。
     */
    @GetMapping("/download")
    public void download(@PathVariable("id") String id,
                         @RequestParam("path") String path,
                         HttpServletResponse response) {
        long begin = System.currentTimeMillis();
        ServerConfig cfg = null;
        SshSession s = null;
        String staging = null;
        try {
            if (ShellUtil.isBlank(path) || !path.trim().startsWith("/") || path.trim().equals("/")) {
                throw new IllegalArgumentException("只能下载绝对路径的文件");
            }
            cfg = store.getForConnect(id);
            String target = path.trim();
            String name = new File(target).getName();
            s = sshService.open(cfg);
            // 中转：cp 到中转目录（默认 /tmp，可通过 deploy.staging-dir 改到非 /tmp）并 chmod +r，使 root-only 文件也可被登录账号读取
            String deployStaging = s.stagingPath("deploy-staging");
            staging = deployStaging + "/" + UUID.randomUUID().toString().replace("-", "") + "-" + name;
            s.mkdirs(deployStaging);
            CommandResult stage = s.execPrivileged(s.getConfig(),
                    "cp -f " + ShellUtil.quote(target) + " " + ShellUtil.quote(staging)
                    + " && chmod +r " + ShellUtil.quote(staging));
            if (!stage.isSuccess()) {
                String hint = ShellUtil.isNotBlank(stage.getOutput()) ? stage.getOutput().trim() : "复制文件失败";
                if (hint.toLowerCase().contains("no such file") || hint.toLowerCase().contains("cannot stat")) {
                    hint = "文件不存在: " + target;
                } else if (hint.toLowerCase().contains("permission denied")) {
                    hint = "无读权限（Permission denied）：请开启「su 切换 root」后重试";
                }
                operationLogService.record(cfg, "SFTP_DOWNLOAD", false, System.currentTimeMillis() - begin, hint);
                writeDownloadError(response, hint);
                return;
            }
            long size = s.remoteSize(staging);
            // 设置响应头
            response.setContentType("application/octet-stream");
            String encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
            String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
            if (size > 0) {
                response.setContentLengthLong(size);
            }
            // 流式回传
            OutputStream out = response.getOutputStream();
            s.downloadToStream(staging, out);
            out.flush();
            response.flushBuffer();
            operationLogService.record(cfg, "SFTP_DOWNLOAD", true, System.currentTimeMillis() - begin,
                    name + " ← " + target + " (" + size + "B)");
        } catch (Exception e) {
            if (cfg != null) {
                operationLogService.record(cfg, "SFTP_DOWNLOAD", false, System.currentTimeMillis() - begin, e.getMessage());
            }
            if (!response.isCommitted()) {
                writeDownloadError(response, e.getMessage());
            }
        } finally {
            // 清理中转文件（登录账号对 /tmp/deploy-staging 有写权限，可删 root 创建的文件）
            if (s != null && staging != null) {
                try {
                    s.execRaw("rm -f " + ShellUtil.quote(staging));
                } catch (Exception ignored) {
                    // 清理失败不影响主流程
                }
            }
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    /** 下载接口的错误响应：返回 HTTP 500 + JSON，前端 fetch 据 res.ok 判定失败并读取 message */
    private void writeDownloadError(HttpServletResponse response, String message) {
        try {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json;charset=UTF-8");
            R<Void> r = R.fail(message == null ? "下载失败" : message);
            response.getWriter().write(objectMapper.writeValueAsString(r));
        } catch (Exception ignored) {
            // 响应已无法写入时忽略
        }
    }

    private File saveTemp(MultipartFile file, String name) {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "deploy-sftp");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("创建临时目录失败");
            }
            File target = File.createTempFile("sftp-", "-" + name, dir);
            file.transferTo(target.getAbsoluteFile());
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
    }
}
