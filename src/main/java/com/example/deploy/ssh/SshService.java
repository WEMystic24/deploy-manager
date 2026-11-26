package com.example.deploy.ssh;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.deploy.config.DeployProperties;
import com.example.deploy.model.DynamicCodeRequiredException;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.service.DynamicCodeStore;
import com.example.deploy.service.OperationLogService;
import com.example.deploy.util.ShellUtil;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

/**
 * SSH 连接工厂 + 连接池：支持账号密码登录，也支持私钥登录。
 * <p>
 * 连接复用策略（不是定时重连，而是「懒重建 + 空闲回收」）：
 * <ol>
 *   <li>按 {@code 账号@主机:端口} 缓存一条已登录的连接，同机同账号的后续操作只在这条连接上开新通道；</li>
 *   <li>借用时若连接已断开，或配置指纹（密码/私钥）变了，立即重建再借用；</li>
 *   <li>归还时不断开，只标记空闲；空闲超过 {@link #IDLE_TIMEOUT_MS} 由后台线程回收；</li>
 *   <li>执行中若发现连接被对端断开，自动重连一次并重试当前命令；</li>
 *   <li>应用关闭时统一断开所有缓存连接。</li>
 * </ol>
 */
@Component
public class SshService {

    private static final Logger log = LoggerFactory.getLogger(SshService.class);

    /** 连接空闲多久后主动断开（毫秒），避免服务器端堆积无用的会话 */
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000L;
    /** 空闲检查间隔（秒） */
    private static final long IDLE_SCAN_SECONDS = 60L;
    /** 建连失败后的熔断窗口（毫秒）：窗口内的并发请求直接失败，不再各自等满超时 */
    private static final long FAIL_BACKOFF_MS = 3000L;

    private final DeployProperties properties;
    private final OperationLogService operationLogService;
    private final DynamicCodeStore dynamicCodeStore;

    /** 连接池：key = 账号@主机:端口 */
    private final ConcurrentHashMap<String, Pooled> pool = new ConcurrentHashMap<>();
    /** 每台服务器的建连锁，避免并发首次访问时重复登录 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    /** 最近一次连接失败记录，用于短时间熔断 */
    private final ConcurrentHashMap<String, Failure> failures = new ConcurrentHashMap<>();

    private ScheduledExecutorService cleaner;

    public SshService(DeployProperties properties, OperationLogService operationLogService,
                      DynamicCodeStore dynamicCodeStore) {
        this.properties = properties;
        this.operationLogService = operationLogService;
        this.dynamicCodeStore = dynamicCodeStore;
    }

    /**
     * 借出一条会话：优先复用缓存连接，不可用则新建。调用方负责 close（try-with-resources 即可），
     * 池化模式下 close 只是归还，不会断开连接。
     */
    public SshSession open(ServerConfig config) {
        if (config == null || ShellUtil.isBlank(config.getHost()) || ShellUtil.isBlank(config.getUsername())) {
            throw new IllegalArgumentException("服务器地址或登录账号未配置");
        }
        String key = poolKey(config);
        String fingerprint = fingerprint(config);
        Pooled pooled;
        synchronized (lockOf(key)) {
            pooled = pool.get(key);
            if (pooled != null && (!pooled.isAlive() || !fingerprint.equals(pooled.fingerprint))) {
                if (pool.remove(key, pooled)) {
                    pooled.destroy();
                }
                log.debug("复用条件不满足（连接已断开或配置变更），重建: {}", key);
                pooled = null;
            }
            if (pooled == null) {
                // 刚失败过的服务器短时间内直接拒绝，避免多个并发请求逐个等满连接超时
                Failure last = failures.get(key);
                long now = System.currentTimeMillis();
                if (last != null && now - last.at < FAIL_BACKOFF_MS) {
                    throw new IllegalStateException("连接服务器失败（刚失败过，请稍后重试）: " + last.message);
                }
                try {
                    pooled = new Pooled(connect(config), fingerprint, config);
                    failures.remove(key);
                    pool.put(key, pooled);
                } catch (RuntimeException e) {
                    failures.put(key, new Failure(now, e.getMessage()));
                    throw e;
                }
            }
            pooled.inUse.incrementAndGet();
            pooled.lastUsedAt = System.currentTimeMillis();
        }
        return new SshSession(pooled.session, config, properties.getExecTimeoutSeconds(), operationLogService,
                properties.getStagingDir(), new PoolCallback(key, pooled), pooled.suLogged);
    }

    /**
     * 该服务器当前是否已有可直接复用的存活连接（指纹一致、未断开）。
     *
     * <p>动态码只在「新建连接」时参与认证，复用已有连接时不会再校验。
     * 所以判断「要不要再向用户要一次动态码」时应当看本方法，而不能只看动态码缓存是否还没过期：
     * 只要连接一直活着（持续操作、心跳保活），即使动态码缓存过了有效期，本次操作也不需要重新登录。</p>
     */
    public boolean hasLiveConnection(ServerConfig config) {
        if (config == null || ShellUtil.isBlank(config.getHost()) || ShellUtil.isBlank(config.getUsername())) {
            return false;
        }
        Pooled pooled = pool.get(poolKey(config));
        return pooled != null && pooled.isAlive() && fingerprint(config).equals(pooled.fingerprint);
    }

    /** 主动断开并移除某台服务器的缓存连接（改密码、删除服务器等场景可调用） */
    public void invalidate(ServerConfig config) {
        if (config == null) {
            return;
        }
        Pooled removed = pool.remove(poolKey(config));
        if (removed != null) {
            removed.destroy();
            log.debug("已失效缓存连接: {}", poolKey(config));
        }
    }

    /** 建立连接并记录操作日志（仅新建/重连时发生，复用时不记录） */
    private Session connect(ServerConfig config) {
        long begin = System.currentTimeMillis();
        try {
            JSch jsch = new JSch();
            if (config.usePrivateKey()) {
                File key = new File(config.getPrivateKeyPath());
                if (!key.exists()) {
                    throw new IllegalArgumentException("私钥文件不存在: " + key.getAbsolutePath());
                }
                if (ShellUtil.isNotBlank(config.getPassphrase())) {
                    jsch.addIdentity(key.getAbsolutePath(), config.getPassphrase());
                } else {
                    jsch.addIdentity(key.getAbsolutePath());
                }
            }

            // 动态码：拼接到密码末尾一起做认证（MFA 常见形式）。没有有效动态码时快速失败，
            // 由前端弹窗让用户输入后重试，避免先发起一次注定失败的登录。
            String password = effectivePassword(config);

            Session session = jsch.getSession(config.getUsername().trim(),
                    config.getHost().trim(), config.effectivePort());
            if (!config.usePrivateKey()) {
                session.setPassword(password);
            }
            session.setUserInfo(new PasswordUserInfo(password));

            Properties cfg = new Properties();
            // 免去首次连接的 host key 交互确认
            cfg.put("StrictHostKeyChecking", "no");
            cfg.put("PreferredAuthentications", config.usePrivateKey()
                    ? "publickey,password,keyboard-interactive"
                    : "password,keyboard-interactive,publickey");
            // 兼容部分旧服务器
            cfg.put("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa");
            session.setConfig(cfg);
            // 长连接必须靠心跳保活，否则中间设备/sshd 会掐掉空闲会话
            session.setServerAliveInterval(30000);
            session.setServerAliveCountMax(3);
            session.connect(properties.getConnectTimeoutSeconds() * 1000);

            long cost = System.currentTimeMillis() - begin;
            operationLogService.record(config, "CONNECT", true, cost, "登录账号 " + config.getUsername()
                    + (config.isDynamicCodeEnabled() ? "（含动态码）" : ""));
            log.info("SSH 连接成功 {}@{}:{}{} ({}ms)", config.getUsername(), config.getHost(),
                    config.effectivePort(), config.isDynamicCodeEnabled() ? "（动态码）" : "", cost);
            return session;
        } catch (DynamicCodeRequiredException e) {
            // 缺少动态码不是连接失败，直接上抛给前端弹窗，不记失败日志、不进入熔断
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - begin;
            // 动态码错误/过期的典型表现是认证失败：清掉已存的动态码，下次连接重新提示输入
            if (config.isDynamicCodeEnabled() && looksLikeAuthFailure(e.getMessage())) {
                dynamicCodeStore.clear(config.getId());
                log.info("服务器 {} 认证失败，已清除缓存的动态码，下次连接需重新输入", config.getId());
            }
            operationLogService.record(config, "CONNECT", false, cost, e.getMessage());
            log.warn("连接服务器失败 {}@{}:{} -> {}", config.getUsername(), config.getHost(),
                    config.effectivePort(), e.getMessage());
            throw new IllegalStateException("连接服务器失败: " + e.getMessage(), e);
        }
    }

    /**
     * 实际用于认证的密码：开启动态码时把动态码拼接到密码末尾。
     * 没有有效动态码则抛 {@link DynamicCodeRequiredException}，由前端弹窗输入后重试。
     */
    private String effectivePassword(ServerConfig config) {
        String password = config.getPassword() == null ? "" : config.getPassword();
        if (!config.isDynamicCodeEnabled()) {
            return password;
        }
        String code = dynamicCodeStore.get(config.getId());
        if (ShellUtil.isBlank(code)) {
            throw new DynamicCodeRequiredException(config.getId(), config.getName());
        }
        return password + code;
    }

    /** 认证失败判定（JSch 报 "Auth fail" / "Auth cancel"，部分服务器报 "auth failed"） */
    private static boolean looksLikeAuthFailure(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("auth") || m.contains("密码") || m.contains("permission denied");
    }

    private Object lockOf(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    /** 池化 key：同机同账号的不同发布配置可共用一条连接 */
    private static String poolKey(ServerConfig c) {
        return c.getUsername().trim() + "@" + c.getHost().trim() + ":" + c.effectivePort();
    }

    /** 配置指纹：登录方式、密码、私钥变化时视为需要重建连接 */
    private static String fingerprint(ServerConfig c) {
        StringBuilder sb = new StringBuilder(poolKey(c));
        sb.append('|').append(c.usePrivateKey() ? "key" : "pwd");
        if (c.usePrivateKey()) {
            sb.append('|').append(c.getPrivateKeyPath());
            sb.append('|').append(hashOf(c.getPassphrase()));
        }
        sb.append('|').append(hashOf(c.getPassword()));
        return sb.toString();
    }

    /** 仅用于比对配置是否变化，不做安全用途 */
    private static String hashOf(String s) {
        return s == null || s.isEmpty() ? "0" : Integer.toHexString(s.hashCode()) + ":" + s.length();
    }

    @PostConstruct
    void startCleaner() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "ssh-pool-cleaner");
            t.setDaemon(true);
            return t;
        };
        cleaner = Executors.newSingleThreadScheduledExecutor(tf);
        cleaner.scheduleWithFixedDelay(this::evictIdle, IDLE_SCAN_SECONDS, IDLE_SCAN_SECONDS, TimeUnit.SECONDS);
    }

    /** 回收空闲/失效连接：正在使用的（inUse > 0）一律跳过 */
    private void evictIdle() {
        long now = System.currentTimeMillis();
        for (String key : pool.keySet()) {
            Pooled p = pool.get(key);
            if (p == null || p.inUse.get() > 0) {
                continue;
            }
            boolean dead = !p.isAlive();
            if (!dead && now - p.lastUsedAt <= IDLE_TIMEOUT_MS) {
                continue;
            }
            if (pool.remove(key, p)) {
                p.destroy();
                log.debug("回收{}的 SSH 连接: {}", dead ? "已断开" : "空闲", key);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
        for (Pooled p : pool.values()) {
            p.destroy();
        }
        pool.clear();
    }

    /** 一次连接失败记录（熔断用） */
    private static final class Failure {
        final long at;
        final String message;

        Failure(long at, String message) {
            this.at = at;
            this.message = message;
        }
    }

    /** 池中的一条连接 */
    private static final class Pooled {
        /** 可被重连替换 */
        volatile Session session;
        final String fingerprint;
        final ServerConfig config;
        final AtomicInteger inUse = new AtomicInteger();
        /** su 审计标记：跨会话共享，连接复用时不会重复记 SU 日志 */
        final AtomicBoolean suLogged = new AtomicBoolean(false);
        volatile long lastUsedAt = System.currentTimeMillis();

        Pooled(Session session, String fingerprint, ServerConfig config) {
            this.session = session;
            this.fingerprint = fingerprint;
            this.config = config;
        }

        boolean isAlive() {
            Session s = session;
            return s != null && s.isConnected();
        }

        void destroy() {
            try {
                Session s = session;
                if (s != null && s.isConnected()) {
                    s.disconnect();
                }
            } catch (Exception ignored) {
                // 断开失败不影响主流程
            }
        }
    }

    /** 池化模式回调：重连与归还 */
    private final class PoolCallback implements SshSession.PoolCallback {

        private final String key;
        private final Pooled pooled;

        PoolCallback(String key, Pooled pooled) {
            this.key = key;
            this.pooled = pooled;
        }

        @Override
        public Session reconnect() {
            synchronized (lockOf(key)) {
                // 可能已被其它线程重连好了，直接用最新的
                if (pooled.isAlive()) {
                    return pooled.session;
                }
                log.info("SSH 连接已断开，正在重连 {}", key);
                Session fresh = connect(pooled.config);
                pooled.session = fresh;
                pooled.lastUsedAt = System.currentTimeMillis();
                // 若期间被回收出池，重新放回，让后续操作继续复用
                pool.putIfAbsent(key, pooled);
                return fresh;
            }
        }

        @Override
        public void release(Session current) {
            if (!pooled.isAlive()) {
                if (pool.remove(key, pooled)) {
                    pooled.destroy();
                }
                return;
            }
            if (current != null && current.isConnected()) {
                pooled.session = current;
            }
            if (pooled.inUse.decrementAndGet() <= 0) {
                pooled.inUse.set(0);
                pooled.lastUsedAt = System.currentTimeMillis();
            }
        }
    }

    /**
     * 支持只开放 keyboard-interactive 认证的服务器。
     */
    private static class PasswordUserInfo implements UserInfo, UIKeyboardInteractive {

        private final String password;

        PasswordUserInfo(String password) {
            this.password = password;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return true;
        }

        @Override
        public boolean promptYesNo(String message) {
            return true;
        }

        @Override
        public void showMessage(String message) {
            // 忽略服务器 banner
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction,
                                                  String[] prompt, boolean[] echo) {
            if (prompt == null || prompt.length == 0) {
                return new String[0];
            }
            String[] answers = new String[prompt.length];
            for (int i = 0; i < prompt.length; i++) {
                answers[i] = password;
            }
            return answers;
        }
    }
}
