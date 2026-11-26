package com.example.deploy.controller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.deploy.model.CommandResult;
import com.example.deploy.model.CompleteResult;
import com.example.deploy.model.R;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.service.OperationLogService;
import com.example.deploy.ssh.SshService;
import com.example.deploy.ssh.SshSession;
import com.example.deploy.store.ServerConfigStore;
import com.example.deploy.util.ShellUtil;

/**
 * 远程命令行执行接口：在目标服务器上执行任意 shell 命令并返回输出。
 *
 * <p>模拟「终端」交互：为每台服务器维护一个当前工作目录（cwd），
 * {@code cd} 切换目录后，后续命令都会在该目录下执行；每个执行结果都会带回
 * 其所在目录，便于前端像终端一样展示「当前目录 + 执行结果」。</p>
 *
 * <p>命令经 {@code execPrivileged} 执行（启用 su 时以 root 身份、走 login shell），
 * 便于在线排查问题、查看文件、执行临时脚本等。超长输出会被截断，避免响应过大。</p>
 */
@RestController
@RequestMapping("/api/servers/{id}")
public class CommandController {

    /** 单条 stdout / stderr 最大保留字符数，超出截断，防止前端卡顿 / 内存溢出 */
    private static final int MAX_STDOUT = 100000;
    private static final int MAX_STDERR = 50000;

    /** vi 在线编辑的单文件大小上限（字节），防止大文件 / 二进制文件把网页编辑器拖垮 */
    private static final long MAX_VI_SIZE = 2 * 1024 * 1024L;

    /** 形如 cd、cd /opt/app、cd ~、cd - 的目录切换命令（整条命令即 cd） */
    private static final Pattern CD_PATTERN = Pattern.compile("^\\s*cd(\\s+.*)?$");

    /** 每台服务器（serverId）的终端当前目录，内存态，服务重启后回到登录默认目录 */
    private final ConcurrentMap<String, String> cwdCache = new ConcurrentHashMap<String, String>();

    /** 进行中的流式命令（sessionId -> 可中断句柄），用于 tail -f 等持续命令的停止 */
    private final ConcurrentMap<String, SshSession.StreamingExec> streamingMap =
            new ConcurrentHashMap<String, SshSession.StreamingExec>();

    private final ServerConfigStore store;
    private final SshService sshService;
    private final OperationLogService operationLogService;

    public CommandController(ServerConfigStore store, SshService sshService,
                             OperationLogService operationLogService) {
        this.store = store;
        this.sshService = sshService;
        this.operationLogService = operationLogService;
    }

    /**
     * 执行远程命令，返回 stdout / stderr / 退出码 / 耗时 / 所在目录。
     * 命令以登录账号身份执行；启用 su 时切换到目标用户（如 root）执行。
     *
     * @param reset 为 true 时先重置该服务器的终端目录（切服务器后首次执行可传）
     */
    @PostMapping("/exec")
    public R<CommandResult> exec(@PathVariable("id") String id,
                                 @RequestParam("command") String command,
                                 @RequestParam(value = "reset", required = false) Boolean reset) {
        if (ShellUtil.isBlank(command)) {
            throw new IllegalArgumentException("命令不能为空");
        }
        String cmd = command.trim();
        if (Boolean.TRUE.equals(reset)) {
            cwdCache.remove(id);
        }
        ServerConfig cfg = store.getForConnect(id);
        long begin = System.currentTimeMillis();
        try (SshSession s = sshService.open(cfg)) {
            CommandResult r = isCdCommand(cmd)
                    ? execCd(s, cfg, id, cmd)
                    : execAtCwd(s, cfg, id, cmd);
            truncate(r);
            operationLogService.record(cfg, "EXEC", r.isSuccess(), System.currentTimeMillis() - begin,
                    ShellUtil.abbreviate(cmd, 200));
            return R.ok(r);
        } catch (Exception e) {
            operationLogService.record(cfg, "EXEC", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /** 重置该服务器的终端当前目录（回到登录默认目录） */
    @PostMapping("/exec/reset")
    public R<Void> resetCwd(@PathVariable("id") String id) {
        cwdCache.remove(id);
        return R.ok();
    }

    /**
     * Tab 自动补全：解析输入行最后一个词作为前缀，在终端当前目录（cwd）下用
     * {@code compgen} 匹配候选路径。cd 类命令只补全目录，其他命令补全文件与目录。
     * 目录候选以 "/" 结尾。补全失败时静默返回空候选，不影响终端使用。
     */
    @PostMapping("/exec/complete")
    public R<CompleteResult> complete(@PathVariable("id") String id,
                                      @RequestParam("input") String input) {
        ServerConfig cfg = store.getForConnect(id);
        String prefix = lastToken(input);
        boolean dirOnly = isCdCommand(ShellUtil.trimToEmpty(input));
        try (SshSession s = sshService.open(cfg)) {
            return R.ok(completeAtCwd(s, cfg, id, prefix, dirOnly));
        }
    }

    /**
     * 流式执行命令（tail -f、top 等持续输出命令）：SSE 实时推送输出，支持中途停止。
     * 前端用 EventSource 订阅「out」事件（实时输出片段）与「exit」事件（退出码）；
     * 需要停止时调用 {@code POST /exec/stream/{sessionId}/stop} 断开远端进程。
     */
    @GetMapping("/exec/stream")
    public SseEmitter stream(@PathVariable("id") String id,
                             @RequestParam("command") String command,
                             @RequestParam("sessionId") String sessionId) {
        ServerConfig cfg = store.getForConnect(id);
        String cwd = cwdCache.get(id);
        final String cmd = command.trim();
        final String full = ShellUtil.isNotBlank(cwd)
                ? "cd " + ShellUtil.quote(cwd) + " && " + cmd
                : cmd;
        final SseEmitter emitter = new SseEmitter(0L); // 不设超时，直到命令结束或停止
        emitter.onCompletion(new Runnable() {
            @Override
            public void run() {
                stopStreaming(sessionId);
            }
        });
        emitter.onTimeout(new Runnable() {
            @Override
            public void run() {
                stopStreaming(sessionId);
            }
        });
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                SshSession.StreamingExec se = null;
                try (SshSession s = sshService.open(cfg)) {
                    final SshSession.StreamingExec[] holder = new SshSession.StreamingExec[1];
                    se = s.execStreaming(cfg, full, new SshSession.OutputListener() {
                        @Override
                        public void onOutput(String chunk) {
                            try {
                                emitter.send(SseEmitter.event().name("out").data(chunk));
                            } catch (Exception e) {
                                // 客户端已断开，中断远端命令
                                if (holder[0] != null) {
                                    holder[0].stop();
                                }
                            }
                        }

                        @Override
                        public void onExit(int code) {
                            try {
                                emitter.send(SseEmitter.event().name("exit").data(code));
                            } catch (Exception ignored) {
                                // ignore
                            } finally {
                                emitter.complete();
                            }
                        }
                    });
                    holder[0] = se;
                    streamingMap.put(sessionId, se);
                } catch (Exception e) {
                    try {
                        emitter.send(SseEmitter.event().name("out").data("\n[执行异常] " + e.getMessage()));
                    } catch (Exception ignored) {
                        // ignore
                    }
                    emitter.complete();
                } finally {
                    streamingMap.remove(sessionId);
                }
            }
        }, "stream-" + sessionId);
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    /** 停止进行中的流式命令（断开远端 exec 通道） */
    @PostMapping("/exec/stream/{sessionId}/stop")
    public R<Void> stopStream(@PathVariable("id") String id,
                              @PathVariable("sessionId") String sessionId) {
        stopStreaming(sessionId);
        return R.ok();
    }

    private void stopStreaming(String sessionId) {
        SshSession.StreamingExec se = streamingMap.remove(sessionId);
        if (se != null) {
            se.stop();
        }
    }

    /** 取输入行最后一个词（Tab 补全的前缀）；无空格则整行作为前缀 */
    private String lastToken(String input) {
        if (input == null) {
            return "";
        }
        int sp = input.lastIndexOf(' ');
        return sp >= 0 ? input.substring(sp + 1) : input;
    }

    /** 在终端 cwd 下执行 compgen 匹配候选，返回目录（带 / 后缀）+ 文件 */
    private CompleteResult completeAtCwd(SshSession s, ServerConfig cfg, String id,
                                         String prefix, boolean dirOnly) {
        String cwd = cwdCache.get(id);
        CompleteResult out = new CompleteResult();
        out.setCwd(cwd);
        StringBuilder script = new StringBuilder();
        if (ShellUtil.isNotBlank(cwd)) {
            script.append("cd ").append(ShellUtil.quote(cwd)).append(" && ");
        }
        script.append("echo '__COMP_D__'; ")
              .append("compgen -d -- ").append(ShellUtil.quote(prefix)).append("; ");
        if (!dirOnly) {
            script.append("echo '__COMP_F__'; ")
                  .append("compgen -f -- ").append(ShellUtil.quote(prefix)).append("; ");
        }
        script.append("echo '__COMP_E__'");
        CommandResult r = s.execPrivileged(cfg, script.toString());
        if (!r.isSuccess()) {
            return out;
        }
        Set<String> dirs = new LinkedHashSet<String>();
        List<String> files = new ArrayList<String>();
        parseCompSections(r.getOutput(), dirs, files);
        List<String> candidates = new ArrayList<String>();
        for (String d : dirs) {
            candidates.add(d + "/");
        }
        for (String f : files) {
            if (!dirs.contains(f)) {
                candidates.add(f);
            }
        }
        out.setCandidates(candidates);
        return out;
    }

    /** 解析 compgen 输出：__COMP_D__ 段为目录、__COMP_F__ 段为文件（su 密码提示等噪声行被忽略） */
    private void parseCompSections(String out, Set<String> dirs, List<String> files) {
        if (out == null) {
            return;
        }
        boolean inDirs = false;
        boolean inFiles = false;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if ("__COMP_D__".equals(t)) {
                inDirs = true;
                inFiles = false;
            } else if ("__COMP_F__".equals(t)) {
                inDirs = false;
                inFiles = true;
            } else if ("__COMP_E__".equals(t)) {
                inDirs = false;
                inFiles = false;
            } else if (t.length() > 0) {
                if (inDirs) {
                    dirs.add(t);
                } else if (inFiles) {
                    files.add(t);
                }
            }
        }
    }

    /** 判断整条命令是否为目录切换命令 */
    private boolean isCdCommand(String cmd) {
        return CD_PATTERN.matcher(cmd).matches();
    }

    /**
     * 执行 cd：切换到目标目录并 pwd 确认，成功后更新该服务器的 cwd；
     * 失败时保留原目录并把错误作为输出返回。
     */
    private CommandResult execCd(SshSession s, ServerConfig cfg, String id, String cmd) {
        String target = cmd.length() > 2 ? cmd.substring(cmd.indexOf("cd") + 2).trim() : "";
        String base = cwdCache.get(id);
        String dirCmd;
        if (target.isEmpty()) {
            dirCmd = "cd && pwd && echo '---LIST---' && ls -F";
        } else if ("-".equals(target)) {
            dirCmd = "cd - && pwd && echo '---LIST---' && ls -F";
        } else if ("~".equals(target)) {
            dirCmd = "cd \"$HOME\" && pwd && echo '---LIST---' && ls -F";
        } else if (target.startsWith("~/")) {
            dirCmd = "cd \"$HOME\"/" + ShellUtil.quote(target.substring(2))
                    + " && pwd && echo '---LIST---' && ls -F";
        } else {
            dirCmd = "cd " + ShellUtil.quote(target) + " && pwd && echo '---LIST---' && ls -F";
        }
        // 每次执行都是全新会话（shell 状态不保留），须先进入终端当前目录，
        // 相对路径（cd logs、cd ..、cd sub/）才能基于当前目录正确解析
        String full = ShellUtil.isNotBlank(base)
                ? "cd " + ShellUtil.quote(base) + " && " + dirCmd
                : dirCmd;
        CommandResult r = s.execPrivileged(cfg, full);
        r.setUser(execUser(cfg));
        if (r.isSuccess()) {
            // 分隔符前是 pwd 结果（su 提权时最前面可能有「密码：」提示，取最后非空行）；
            // 分隔符后是 ls -F 列出的目录内容
            String pwd = lastNonEmptyLine(sectionBefore(r.getOutput(), "---LIST---"));
            if (ShellUtil.isNotBlank(pwd)) {
                cwdCache.put(id, pwd);
                r.setCwd(pwd);
                String listing = sectionAfter(r.getOutput(), "---LIST---");
                r.setStdout("已切换到目录：" + pwd
                        + (ShellUtil.isBlank(listing) ? "" : "\n" + listing));
                r.setStderr("");
            } else {
                r.setCwd(cwdCache.get(id));
            }
        } else {
            r.setCwd(cwdCache.get(id));
            String err = r.getOutput();
            r.setStdout("切换目录失败：" + (ShellUtil.isBlank(err) ? "目标目录不存在或无权限" : err.trim()));
            r.setStderr("");
        }
        return r;
    }

    /** 在 cwd 目录下执行普通命令，并把所在目录带回结果 */
    private CommandResult execAtCwd(SshSession s, ServerConfig cfg, String id, String cmd) {
        String cwd = cwdCache.get(id);
        String full = ShellUtil.isNotBlank(cwd)
                ? "cd " + ShellUtil.quote(cwd) + " && " + cmd
                : cmd;
        CommandResult r = s.execPrivileged(cfg, full);
        r.setCwd(cwd);
        r.setUser(execUser(cfg));
        return r;
    }

    /** 命令实际执行身份：开启 su 提权时为目标用户（默认 root），否则为登录账号 */
    private String execUser(ServerConfig cfg) {
        return cfg.isSuEnabled() ? cfg.effectiveSuUser() : cfg.getUsername();
    }

    /** 取输出中最后一个非空行（su 提权时前面可能有「密码：」提示，需取最后的 pwd 结果） */
    private String lastNonEmptyLine(String text) {
        if (ShellUtil.isBlank(text)) {
            return "";
        }
        String last = "";
        for (String l : text.split("\n")) {
            if (l.trim().length() > 0) {
                last = l.trim();
            }
        }
        return last;
    }

    /** 取文本中分隔符之前的片段；无分隔符时返回全文 */
    private String sectionBefore(String text, String marker) {
        int idx = text == null ? -1 : text.indexOf(marker);
        return idx >= 0 ? text.substring(0, idx) : text;
    }

    /** 取文本中分隔符之后的片段（去除首尾空白）；无分隔符时返回空串 */
    private String sectionAfter(String text, String marker) {
        if (text == null) {
            return "";
        }
        int idx = text.indexOf(marker);
        return idx >= 0 ? text.substring(idx + marker.length()).trim() : "";
    }

    /** 截断过长输出，避免一次命令把整个大文件回传导致前端卡死 */
    private void truncate(CommandResult r) {
        if (r == null) {
            return;
        }
        String out = r.getStdout();
        if (out != null && out.length() > MAX_STDOUT) {
            r.setStdout(out.substring(0, MAX_STDOUT)
                    + "\n...(输出过长，已截断，共 " + out.length() + " 字符)");
        }
        String err = r.getStderr();
        if (err != null && err.length() > MAX_STDERR) {
            r.setStderr(err.substring(0, MAX_STDERR)
                    + "\n...(已截断，共 " + err.length() + " 字符)");
        }
    }

    /**
     * vi 在线编辑：加载远程文件内容（命令行页面拦截 vi/vim 命令后调用）。
     * path 支持相对路径（基于终端 cwd）与绝对路径；文件不存在时 exists=false，
     * 前端按「新文件」展示（保存时创建），与 vi 行为一致。
     * 文件读取走 /tmp 中转 + 提权 cp，root-only 文件也能读到。
     */
    @PostMapping("/exec/vi/load")
    public R<Map<String, Object>> viLoad(@PathVariable("id") String id,
                                         @RequestBody Map<String, String> body) {
        String path = body == null ? null : body.get("path");
        if (ShellUtil.isBlank(path)) {
            throw new IllegalArgumentException("缺少文件路径");
        }
        ServerConfig cfg = store.getForConnect(id);
        long begin = System.currentTimeMillis();
        try (SshSession s = sshService.open(cfg)) {
            String abs = resolveAbsolute(s, cfg, body.get("cwd"), path);
            String kind = lastNonEmptyLine(s.execPrivileged(cfg, "if [ -d " + ShellUtil.quote(abs)
                    + " ]; then echo DIR; elif [ -e " + ShellUtil.quote(abs)
                    + " ]; then echo FILE; else echo MISSING; fi").getOutput());
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            res.put("path", abs);
            res.put("exists", !"MISSING".equals(kind));
            res.put("dir", "DIR".equals(kind));
            res.put("content", "");
            if (!"FILE".equals(kind)) {
                operationLogService.record(cfg, "VI_LOAD", true, System.currentTimeMillis() - begin,
                        "路径 " + abs + (Boolean.TRUE.equals(res.get("dir")) ? "（目录）" : "（新文件）"));
                return R.ok(res);
            }
            long size = s.remoteSize(abs);
            if (size > MAX_VI_SIZE) {
                throw new IllegalStateException("文件过大（" + size + "B），超过在线编辑上限 "
                        + MAX_VI_SIZE + "B，请下载后编辑");
            }
            String deployStaging = s.stagingPath("deploy-staging");
            String staging = deployStaging + "/" + UUID.randomUUID().toString().replace("-", "") + "-vi.tmp";
            s.mkdirs(deployStaging);
            CommandResult stage = s.execPrivileged(cfg, "cp -f " + ShellUtil.quote(abs) + " "
                    + ShellUtil.quote(staging) + " && chmod +r " + ShellUtil.quote(staging));
            if (!stage.isSuccess()) {
                String hint = ShellUtil.isNotBlank(stage.getOutput()) ? stage.getOutput().trim() : "复制文件失败";
                if (hint.toLowerCase().contains("permission denied")) {
                    hint = "无读权限（Permission denied）：请开启「su 切换 root」后重试";
                }
                throw new IllegalStateException("读取文件失败: " + ShellUtil.abbreviate(hint, 200));
            }
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                s.downloadToStream(staging, buf);
                res.put("content", new String(buf.toByteArray(), StandardCharsets.UTF_8));
                res.put("size", size);
            } finally {
                s.execRaw("rm -f " + ShellUtil.quote(staging));
            }
            operationLogService.record(cfg, "VI_LOAD", true, System.currentTimeMillis() - begin,
                    "路径 " + abs + "（" + size + "B）");
            return R.ok(res);
        } catch (Exception e) {
            operationLogService.record(cfg, "VI_LOAD", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /**
     * vi 在线编辑：保存内容回远程文件（相当于 :wq! / :w）。
     * 写入走 /tmp 中转 + 提权 cp 覆盖，兼容目标目录仅 root 可写的场景；
     * 文件不存在时由 cp 创建（新文件）。
     */
    @PostMapping("/exec/vi/save")
    public R<Map<String, Object>> viSave(@PathVariable("id") String id,
                                         @RequestBody Map<String, String> body) {
        String path = body == null ? null : body.get("path");
        String content = body == null ? null : body.get("content");
        if (ShellUtil.isBlank(path) || !path.trim().startsWith("/") || path.trim().equals("/")) {
            throw new IllegalArgumentException("保存路径必须是绝对路径");
        }
        String target = path.trim();
        if (target.endsWith("/")) {
            throw new IllegalArgumentException("保存路径不能是目录: " + target);
        }
        if (content == null) {
            content = "";
        }
        if (content.length() > MAX_VI_SIZE) {
            throw new IllegalStateException("内容过大（" + content.length() + "B），超过保存上限 "
                    + MAX_VI_SIZE + "B");
        }
        ServerConfig cfg = store.getForConnect(id);
        long begin = System.currentTimeMillis();
        File tmp = null;
        try (SshSession s = sshService.open(cfg)) {
            String kind = lastNonEmptyLine(s.execPrivileged(cfg, "if [ -d " + ShellUtil.quote(target)
                    + " ]; then echo DIR; else echo OK; fi").getOutput());
            if ("DIR".equals(kind)) {
                throw new IllegalStateException("目标是目录，无法保存: " + target);
            }
            tmp = File.createTempFile("vi-save-", ".txt");
            Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
            s.uploadViaStaging(tmp, target, null);
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            res.put("path", target);
            res.put("size", content.getBytes(StandardCharsets.UTF_8).length);
            operationLogService.record(cfg, "VI_SAVE", true, System.currentTimeMillis() - begin,
                    "路径 " + target + "（" + res.get("size") + "B）");
            return R.ok(res);
        } catch (Exception e) {
            operationLogService.record(cfg, "VI_SAVE", false, System.currentTimeMillis() - begin, e.getMessage());
            // try 块中的 createTempFile / Files.write 可能抛受检异常 IOException，
            // 不能直接 throw e（方法未声明 throws），统一包装为 RuntimeException 交全局异常处理
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
        }
    }

    /** 基于终端 cwd 解析绝对路径（readlink -f），su 提示噪声取最后一行非空输出 */
    private String resolveAbsolute(SshSession s, ServerConfig cfg, String cwd, String path) {
        StringBuilder sb = new StringBuilder();
        if (ShellUtil.isNotBlank(cwd)) {
            sb.append("cd ").append(ShellUtil.quote(cwd)).append(" && ");
        }
        sb.append("readlink -f -- ").append(ShellUtil.quote(path));
        CommandResult r = s.execPrivileged(cfg, sb.toString());
        if (!r.isSuccess() || ShellUtil.isBlank(r.getOutput())) {
            String err = ShellUtil.isBlank(r.getOutput()) ? "路径解析失败" : r.getOutput().trim();
            throw new IllegalStateException("无法解析文件路径: " + ShellUtil.abbreviate(err, 200));
        }
        return lastNonEmptyLine(r.getOutput());
    }
}
