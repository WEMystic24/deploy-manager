package com.example.deploy.ssh;

import java.io.File;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.deploy.config.DeployProperties;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.service.OperationLogService;
import com.example.deploy.util.ShellUtil;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

/**
 * SSH 连接工厂：支持账号密码登录，也支持私钥登录。
 */
@Component
public class SshService {

    private static final Logger log = LoggerFactory.getLogger(SshService.class);

    private final DeployProperties properties;
    private final OperationLogService operationLogService;

    public SshService(DeployProperties properties, OperationLogService operationLogService) {
        this.properties = properties;
        this.operationLogService = operationLogService;
    }

    /** 建立会话，调用方负责 close（推荐 try-with-resources） */
    public SshSession open(ServerConfig config) {
        if (ShellUtil.isBlank(config.getHost()) || ShellUtil.isBlank(config.getUsername())) {
            throw new IllegalArgumentException("服务器地址或登录账号未配置");
        }
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

            Session session = jsch.getSession(config.getUsername().trim(),
                    config.getHost().trim(), config.effectivePort());
            if (!config.usePrivateKey()) {
                session.setPassword(config.getPassword());
            }
            session.setUserInfo(new PasswordUserInfo(config.getPassword()));

            Properties cfg = new Properties();
            // 免去首次连接的 host key 交互确认
            cfg.put("StrictHostKeyChecking", "no");
            cfg.put("PreferredAuthentications", config.usePrivateKey()
                    ? "publickey,password,keyboard-interactive"
                    : "password,keyboard-interactive,publickey");
            // 兼容部分旧服务器
            cfg.put("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa");
            session.setConfig(cfg);
            session.setServerAliveInterval(30000);
            session.connect(properties.getConnectTimeoutSeconds() * 1000);

            long cost = System.currentTimeMillis() - begin;
            operationLogService.record(config, "CONNECT", true, cost, "登录账号 " + config.getUsername());
            log.info("SSH 连接成功 {}@{}:{} ({}ms)", config.getUsername(), config.getHost(),
                    config.effectivePort(), cost);
            return new SshSession(session, config, properties.getExecTimeoutSeconds(), operationLogService,
                    properties.getStagingDir());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - begin;
            operationLogService.record(config, "CONNECT", false, cost, e.getMessage());
            log.warn("连接服务器失败 {}@{}:{} -> {}", config.getUsername(), config.getHost(),
                    config.effectivePort(), e.getMessage());
            throw new IllegalStateException("连接服务器失败: " + e.getMessage(), e);
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
