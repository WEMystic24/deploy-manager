package com.example.deploy.ssh;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.deploy.model.CommandResult;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.model.SftpDir;
import com.example.deploy.model.SftpEntry;
import com.example.deploy.service.OperationLogService;
import com.example.deploy.util.ShellUtil;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

/**
 * 一次 SSH 会话，支持执行命令与 SFTP 上传，用完即关。
 */
public class SshSession implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SshSession.class);

    private final Session session;
    private final ServerConfig config;
    private final int execTimeoutSeconds;
    private final OperationLogService opLog;
    private final String stagingDir;
    private boolean suLogged = false;

    public SshSession(Session session, ServerConfig config, int execTimeoutSeconds, OperationLogService opLog,
                      String defaultStagingDir) {
        this.session = session;
        this.config = config;
        this.execTimeoutSeconds = execTimeoutSeconds;
        this.opLog = opLog;
        // 优先用服务器自身配置的中转目录（前端可配），为空才回退全局默认
        String perServer = config == null ? null : config.getStagingDir();
        this.stagingDir = normalizeStagingDir(
                (perServer != null && perServer.trim().length() > 0) ? perServer : defaultStagingDir);
    }

    private static String normalizeStagingDir(String dir) {
        String d = (dir == null || dir.trim().isEmpty()) ? "/tmp" : dir.trim();
        while (d.length() > 1 && d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }

    public ServerConfig getConfig() {
        return config;
    }

    /** 远程中转根目录（优先服务器配置的 staging-dir，回退全局 deploy.staging-dir，默认 /tmp） */
    public String getStagingDir() {
        return stagingDir;
    }

    /** 拼接中转目录下的子路径，如 stagingPath("deploy-staging") → /tmp/deploy-staging */
    public String stagingPath(String sub) {
        return stagingDir + "/" + (sub == null ? "" : sub.replaceAll("^/+", ""));
    }

    /**
     * 执行远程命令。默认使用 login shell（bash -l -c），保证 /etc/profile 中的
     * JAVA_HOME、PATH 等环境变量生效，避免出现 "java: command not found"。
     */
    public CommandResult exec(String command) {
        CommandResult result = doExec(wrapLogin(command));
        // 远端没有 bash 时退化为直接执行
        if (result.getExitCode() == 127 && result.getOutput().contains("bash")) {
            log.warn("远端不支持 bash -l，改为直接执行命令");
            result = doExec(command);
        }
        result.setCommand(command);
        return result;
    }

    /** 不做 login shell 包装，直接执行 */
    public CommandResult execRaw(String command) {
        return doExec(command);
    }

    /**
     * 执行远程命令，若配置启用了 su 切换用户（如普通账号登录后切 root），
     * 则通过 PTY 交互式 su 完成提权，避免直连 root 被禁、普通账号又无权操作的问题。
     *
     * 实现要点：
     * 1. 先把脚本内容 SFTP 写到 /tmp 临时文件（用登录账号身份，普通用户家目录/ /tmp 均可写）；
     * 2. 用 PTY 方式执行  su - <user> -c 'bash 临时文件'；
     * 3. 监测到 "Password:" 提示后，把 su 密码写入通道（已禁用终端回显，密码不会进日志）；
     * 4. 执行完删掉临时脚本。
     * su 未启用时退化为普通 exec。
     */
    public CommandResult execPrivileged(ServerConfig cfg, String script) {
        if (cfg == null || !cfg.isSuEnabled()) {
            return exec(script);
        }
        if (ShellUtil.isBlank(cfg.getSuPassword())) {
            CommandResult r = new CommandResult(script);
            r.setExitCode(-1);
            r.setStderr("未配置 su 密码，无法切换到 " + cfg.effectiveSuUser() + " 执行操作");
            return r;
        }
        String tmp = stagingPath("deploy-" + UUID.randomUUID().toString().replace("-", "") + ".sh");
        try {
            mkdirs(stagingDir);
            uploadContent(script, tmp);
        } catch (Exception e) {
            CommandResult r = new CommandResult(script);
            r.setExitCode(-1);
            r.setStderr("写入 su 执行脚本失败: " + e.getMessage());
            return r;
        }
        try {
            CommandResult r = doSu(tmp, cfg);
            logSuOnce(cfg, r);
            return r;
        } finally {
            try {
                execRaw("rm -f " + ShellUtil.quote(tmp));
            } catch (Exception ignored) {
                // 清理失败不影响主流程
            }
        }
    }

    /** su 提权只记录一次（每会话），用于审计；认证失败通过输出特征判断 */
    private void logSuOnce(ServerConfig cfg, CommandResult r) {
        if (suLogged) {
            return;
        }
        suLogged = true;
        boolean ok = !isSuAuthFailure(r);
        opLog.record(cfg, "SU", ok, r.getCostMs(),
                "切换至 " + cfg.effectiveSuUser() + (ok ? "" : "（认证失败，请检查 su 密码）"));
    }

    private static boolean isSuAuthFailure(CommandResult r) {
        String c = (r.getStdout() == null ? "" : r.getStdout())
                + " " + (r.getStderr() == null ? "" : r.getStderr());
        return c.contains("Authentication failure")
                || c.contains("鉴定故障")
                || c.contains("su: 密码")
                || c.contains("su: Permission");
    }

    /** PTY 交互式 su，按提示写入密码 */
    private CommandResult doSu(String tmp, ServerConfig cfg) {
        long start = System.currentTimeMillis();
        CommandResult result = new CommandResult("su - " + cfg.effectiveSuUser());
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("su - " + ShellUtil.quote(cfg.effectiveSuUser())
                    + " -c " + ShellUtil.quote("bash " + tmp));
            // 分配伪终端，否则 util-linux 版的 su（Debian/Ubuntu 默认）只从 /dev/tty 读密码，
            // 管道喂密码会失效；同时关闭回显避免密码出现在输出里。
            channel.setPty(true);
            channel.setTerminalMode(new byte[] { 3, 0, 0, 0, 0, 0 }); // opcode 3=ECHO, value=0, 结尾0结束
            channel.setPtyType("dumb");
            OutputStream stdin = channel.getOutputStream();
            InputStream stdout = channel.getInputStream();
            channel.connect(Math.max(5, execTimeoutSeconds) * 1000);

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long deadline = start + execTimeoutSeconds * 1000L;
            boolean sentPassword = false;
            String passwordLine = cfg.getSuPassword() + "\n";

            while (true) {
                while (stdout.available() > 0) {
                    int len = stdout.read(buffer, 0, buffer.length);
                    if (len < 0) {
                        break;
                    }
                    outBuf.write(buffer, 0, len);
                    if (!sentPassword) {
                        String soFar = new String(outBuf.toByteArray(), StandardCharsets.UTF_8);
                        if (soFar.contains("Password:") || soFar.contains("密码")) {
                            stdin.write(passwordLine.getBytes(StandardCharsets.UTF_8));
                            stdin.flush();
                            sentPassword = true;
                        }
                    }
                }
                if (channel.isClosed()) {
                    while (stdout.available() > 0) {
                        int len = stdout.read(buffer, 0, buffer.length);
                        if (len < 0) {
                            break;
                        }
                        outBuf.write(buffer, 0, len);
                    }
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    result.setExitCode(-1);
                    result.setStderr("su 执行超时（" + execTimeoutSeconds + "s）");
                    return result;
                }
                sleep(80);
            }
            result.setExitCode(channel.getExitStatus());
            String out = new String(outBuf.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
            result.setStdout(out);
            return result;
        } catch (Exception e) {
            result.setExitCode(-1);
            result.setStderr("su 执行异常: " + e.getMessage());
            return result;
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            result.setCostMs(System.currentTimeMillis() - start);
            result.setCommand("su - " + cfg.effectiveSuUser() + " -c 'bash " + tmp + "'");
        }
    }

    /** 流式命令输出回调 */
    public interface OutputListener {
        /** 实时输出片段（已清理 \r，UTF-8 文本） */
        void onOutput(String chunk);

        /** 命令结束（退出码；被停止时可能为负数） */
        void onExit(int exitCode);
    }

    /** 流式执行句柄：调用 {@link #stop()} 可中断远端命令（断开 exec 通道），幂等 */
    public static class StreamingExec implements AutoCloseable {
        private volatile ChannelExec channel;

        void setChannel(ChannelExec c) {
            this.channel = c;
        }

        public void stop() {
            ChannelExec c = channel;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                    // 断开失败不影响主流程
                }
            }
        }

        @Override
        public void close() {
            stop();
        }
    }

    /**
     * 流式执行远程命令（含 su 提权），供 tail -f 等持续输出命令使用。
     * 输出通过 listener 实时回调，调用方可对返回的句柄调用 {@code stop()} 中断。
     * 阻塞直到命令结束（或被停止）。
     */
    public StreamingExec execStreaming(ServerConfig cfg, String script, OutputListener listener) {
        if (cfg == null || !cfg.isSuEnabled() || ShellUtil.isBlank(cfg.getSuPassword())) {
            return doExecStreaming(script, listener);
        }
        final String tmp = stagingPath("deploy-" + UUID.randomUUID().toString().replace("-", "") + ".sh");
        try {
            mkdirs(stagingDir);
            uploadContent(script, tmp);
        } catch (Exception e) {
            listener.onOutput("\n写入 su 执行脚本失败: " + e.getMessage());
            listener.onExit(-1);
            return new StreamingExec();
        }
        StreamingExec handle = doSuStreaming(cfg, tmp, listener);
        // 命令结束（或停止）后清理临时脚本
        try {
            execRaw("rm -f " + ShellUtil.quote(tmp));
        } catch (Exception ignored) {
            // 清理失败不影响主流程
        }
        return handle;
    }

    /**
     * 流式执行（无提权）：PTY 方式让远端按行缓冲输出，保证 tail -f 等实时可见；
     * stderr 与 stdout 合并推送。
     */
    private StreamingExec doExecStreaming(String command, OutputListener listener) {
        final StreamingExec handle = new StreamingExec();
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(wrapLogin(command));
            // PTY：按行缓冲、实时输出；关闭回显避免登录提示等噪声
            channel.setPty(true);
            channel.setTerminalMode(new byte[] { 3, 0, 0, 0, 0, 0 });
            channel.setPtyType("dumb");
            channel.connect(Math.max(5, execTimeoutSeconds) * 1000);
            handle.setChannel(channel);
            InputStream stdout = channel.getInputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                drainStreaming(stdout, buffer, listener);
                if (channel.isClosed()) {
                    drainStreaming(stdout, buffer, listener);
                    break;
                }
                sleep(60);
            }
            listener.onExit(channel.getExitStatus());
        } catch (Exception e) {
            listener.onExit(-1);
        } finally {
            if (channel != null) {
                try {
                    channel.disconnect();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return handle;
    }

    /** 流式执行（su 提权）：PTY 交互 su，按提示写密码，输出实时回调 */
    private StreamingExec doSuStreaming(ServerConfig cfg, String tmp, OutputListener listener) {
        final StreamingExec handle = new StreamingExec();
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("su - " + ShellUtil.quote(cfg.effectiveSuUser())
                    + " -c " + ShellUtil.quote("bash " + tmp));
            channel.setPty(true);
            channel.setTerminalMode(new byte[] { 3, 0, 0, 0, 0, 0 });
            channel.setPtyType("dumb");
            OutputStream stdin = channel.getOutputStream();
            channel.connect(Math.max(5, execTimeoutSeconds) * 1000);
            handle.setChannel(channel);
            InputStream stdout = channel.getInputStream();
            byte[] buffer = new byte[8192];
            boolean sentPassword = false;
            StringBuilder probe = new StringBuilder();
            String passwordLine = cfg.getSuPassword() + "\n";
            while (true) {
                while (stdout.available() > 0) {
                    int len = stdout.read(buffer, 0, buffer.length);
                    if (len < 0) {
                        break;
                    }
                    String chunk = new String(buffer, 0, len, StandardCharsets.UTF_8);
                    probe.append(chunk);
                    if (!sentPassword && (probe.indexOf("Password:") >= 0 || probe.indexOf("密码") >= 0)) {
                        stdin.write(passwordLine.getBytes(StandardCharsets.UTF_8));
                        stdin.flush();
                        sentPassword = true;
                    }
                    listener.onOutput(cleanCarriage(chunk));
                }
                if (channel.isClosed()) {
                    drainStreaming(stdout, buffer, listener);
                    break;
                }
                sleep(60);
            }
            listener.onExit(channel.getExitStatus());
        } catch (Exception e) {
            listener.onExit(-1);
        } finally {
            if (channel != null) {
                try {
                    channel.disconnect();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return handle;
    }

    private void drainStreaming(InputStream in, byte[] buffer, OutputListener listener) throws Exception {
        while (in.available() > 0) {
            int len = in.read(buffer, 0, buffer.length);
            if (len < 0) {
                break;
            }
            String chunk = new String(buffer, 0, len, StandardCharsets.UTF_8);
            listener.onOutput(cleanCarriage(chunk));
        }
    }

    /** PTY 输出常带 \r\n，统一清理为 \n；裸 \r（进度刷新）也按换行处理 */
    private static String cleanCarriage(String s) {
        if (s == null || s.indexOf('\r') < 0) {
            return s;
        }
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String wrapLogin(String command) {
        return "bash -l -c " + ShellUtil.quote(command);
    }

    private CommandResult doExec(String command) {
        long start = System.currentTimeMillis();
        CommandResult result = new CommandResult(command);
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setPty(false);
            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();
            channel.connect(Math.max(5, execTimeoutSeconds) * 1000);

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long deadline = start + execTimeoutSeconds * 1000L;

            while (true) {
                drain(stdout, buffer, outBuf);
                drain(stderr, buffer, errBuf);
                if (channel.isClosed()) {
                    drain(stdout, buffer, outBuf);
                    drain(stderr, buffer, errBuf);
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    result.setExitCode(-1);
                    result.setStderr("命令执行超时（" + execTimeoutSeconds + "s）：" + command);
                    return result;
                }
                sleep(80);
            }
            result.setExitCode(channel.getExitStatus());
            result.setStdout(new String(outBuf.toByteArray(), StandardCharsets.UTF_8));
            result.setStderr(new String(errBuf.toByteArray(), StandardCharsets.UTF_8));
            return result;
        } catch (Exception e) {
            result.setExitCode(-1);
            result.setStderr("命令执行异常: " + e.getMessage());
            return result;
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            result.setCostMs(System.currentTimeMillis() - start);
        }
    }

    private void drain(InputStream in, byte[] buffer, ByteArrayOutputStream out) throws Exception {
        while (in.available() > 0) {
            int len = in.read(buffer, 0, buffer.length);
            if (len < 0) {
                break;
            }
            out.write(buffer, 0, len);
        }
    }

    /** 创建远程目录（含父目录） */
    public CommandResult mkdirs(String dir) {
        return exec("mkdir -p " + ShellUtil.quote(dir));
    }

    /**
     * 列出远程目录内容（含文件与子目录）。
     * 走 execPrivileged（启用 su 时以 root 身份执行），因此即便 /opt 这类仅 root 可见的目录也能列出。
     * 解析 {@code ls -la --time-style=long-iso} 的输出，返回结构化结果。
     */
    public SftpDir listRemote(String path) {
        String cd;
        String target = ShellUtil.isBlank(path) ? "" : path.trim();
        if (ShellUtil.isBlank(path) || target.equals("~")) {
            cd = "cd \"$HOME\"";
        } else {
            cd = "cd " + ShellUtil.quote(target);
        }
        String cmd = cd + " && pwd && echo '---SFTP_SEP---' && ls -la --time-style=long-iso .";
        CommandResult r = execPrivileged(config, cmd);
        if (!r.isSuccess()) {
            throw new IllegalStateException("列目录失败: " + ShellUtil.abbreviate(stripPrompt(r.getOutput()), 300));
        }
        String out = r.getOutput() == null ? "" : r.getOutput();
        String sep = "\n---SFTP_SEP---\n";
        int idx = out.indexOf(sep);
        if (idx < 0) {
            throw new IllegalStateException("无法访问该路径（不存在或无权限）: "
                    + (ShellUtil.isBlank(path) ? "home" : path));
        }
        // pwd 输出在分隔符之前、且是最后一行非空内容；
        // 启用 su 时，su 的“密码：”提示也会出现在最前面，需取最后一行非空行作为真实路径
        String pre = out.substring(0, idx);
        String pwdLine = "";
        for (String l : pre.split("\n")) {
            if (l.trim().length() > 0) {
                pwdLine = l.trim();
            }
        }
        String lsPart = out.substring(idx + sep.length());
        SftpDir dir = new SftpDir();
        dir.path = pwdLine.length() > 0 ? pwdLine : (ShellUtil.isBlank(path) ? "/" : path);
        List<SftpEntry> all = new ArrayList<SftpEntry>();
        for (String line : lsPart.split("\n")) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("total ")) {
                continue;
            }
            String[] p = line.split("\\s+");
            if (p.length < 7) {
                continue;
            }
            String perms = p[0];
            boolean isDir = perms.startsWith("d");
            // 名称可能在第 7 个字段之后（含空格），从时间字段之后截取
            String name = line.substring(line.indexOf(p[6]) + p[6].length()).trim();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            SftpEntry e = new SftpEntry();
            e.name = name;
            e.dir = isDir;
            e.perms = perms;
            e.owner = p[2];
            e.group = p[3];
            try {
                e.size = Long.parseLong(p[4]);
            } catch (NumberFormatException ex) {
                e.size = 0;
            }
            e.modTime = p[5] + " " + p[6];
            all.add(e);
        }
        // 目录在前、文件在后；各自按名称（不区分大小写）排序
        all.sort((a, b) -> {
            if (a.dir != b.dir) {
                return a.dir ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        dir.entries = all;
        return dir;
    }

    /**
     * 上传文件到远程任意路径：先以登录账号身份 SFTP 到 /tmp 中转目录（必可写），
     * 再以 exec（su 场景为 root）把文件 cp 到目标路径，避免目标目录无写权限时报 Permission denied。
     * 传入 monitor 可实时上报传输进度（用于前端进度条）。
     */
    public void uploadViaStaging(File localFile, String remotePath, SftpProgressMonitor monitor) {
        String name = localFile.getName();
        String stagingDir = stagingPath("deploy-staging");
        String staging = stagingDir + "/" + UUID.randomUUID().toString().replace("-", "") + "-" + name;
        mkdirs(stagingDir);
        upload(localFile, staging, monitor);
        CommandResult mv = execPrivileged(config, "cp -f " + ShellUtil.quote(staging)
                + " " + ShellUtil.quote(remotePath) + " && echo MOVED && rm -f " + ShellUtil.quote(staging));
        if (!(mv.isSuccess() && mv.getOutput().contains("MOVED"))) {
            String hint = ShellUtil.isNotBlank(mv.getOutput()) ? mv.getOutput().trim() : "移动失败（未返回 MOVED）";
            if (hint.toLowerCase().contains("permission denied")) {
                hint = "目标目录写权限不足（Permission denied）：请确认登录账号对该目录有写权限，或开启「登录后 su 切换 root」。";
            }
            throw new IllegalStateException("写入远程文件失败: " + hint);
        }
    }

    /**
     * SFTP 上传：先传成 .uploading 临时文件，成功后再改名覆盖目标，
     * 避免上传中断导致远程留下半个损坏的 jar。
     */
    public void upload(File localFile, String remotePath) {
        upload(localFile, remotePath, null);
    }

    /**
     * SFTP 上传：先传成 .uploading 临时文件，成功后再改名覆盖目标，
     * 避免上传中断导致远程留下半个损坏的 jar。
     * 传入 monitor 可实时上报传输进度（用于前端进度条）。
     */
    public void upload(File localFile, String remotePath, SftpProgressMonitor monitor) {
        ChannelSftp sftp = null;
        String tmpPath = remotePath + ".uploading";
        InputStream in = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(Math.max(5, execTimeoutSeconds) * 1000);
            in = new FileInputStream(localFile);
            if (monitor != null) {
                sftp.put(in, tmpPath, monitor);
            } else {
                sftp.put(in, tmpPath);
            }
            try {
                sftp.rm(remotePath);
            } catch (SftpException ignored) {
                // 目标不存在，忽略
            }
            sftp.rename(tmpPath, remotePath);
        } catch (Exception e) {
            throw new IllegalStateException("上传失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(in);
            if (sftp != null) {
                sftp.disconnect();
            }
        }
    }

    /**
     * 把一段文本内容（远程执行脚本）写到远程文件，同样走 .uploading 临时文件再改名，
     * 避免写入过程中断留下半截脚本。
     */
    public void uploadContent(String content, String remotePath) {
        ChannelSftp sftp = null;
        String tmpPath = remotePath + ".uploading";
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(Math.max(5, execTimeoutSeconds) * 1000);
            ByteArrayInputStream in = new ByteArrayInputStream(
                    content.getBytes(StandardCharsets.UTF_8));
            sftp.put(in, tmpPath);
            try {
                sftp.rm(remotePath);
            } catch (SftpException ignored) {
                // 目标不存在，忽略
            }
            sftp.rename(tmpPath, remotePath);
        } catch (Exception e) {
            throw new IllegalStateException("写入远程脚本失败: " + e.getMessage(), e);
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
        }
    }

    /** 获取远程文件大小（字节），失败或不存在返回 -1 */
    public long remoteSize(String remotePath) {
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(Math.max(5, execTimeoutSeconds) * 1000);
            SftpATTRS attrs = sftp.stat(remotePath);
            return attrs.getSize();
        } catch (Exception e) {
            return -1L;
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
        }
    }

    /**
     * 把远程文件内容流式写入输出流（直接 SFTP get，登录账号身份）。
     * 不会关闭传入的 out。用于下载接口把远程文件回传给浏览器。
     * 注意：若文件仅 root 可读且未启用 su，会抛权限错误——调用方应先 cp 到 /tmp 中转并 chmod +r。
     */
    public void downloadToStream(String remotePath, OutputStream out) {
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(Math.max(5, execTimeoutSeconds) * 1000);
            // get(src, out) 把远程文件写入 out，不关闭 out
            sftp.get(remotePath, out);
        } catch (Exception e) {
            throw new IllegalStateException("下载失败: " + e.getMessage(), e);
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
        }
    }

    /** 去掉 su 密码提示（密码：/Password:）等噪声行，仅用于错误展示 */
    private static String stripPrompt(String out) {
        if (out == null) {
            return "";
        }
        String[] lines = out.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String l : lines) {
            String t = l.trim();
            if (first && (t.startsWith("密码") || t.startsWith("Password") || t.startsWith("password"))) {
                first = false;
                continue;
            }
            first = false;
            sb.append(l).append("\n");
        }
        return sb.toString();
    }

    private void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }
}
