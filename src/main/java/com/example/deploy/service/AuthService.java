package com.example.deploy.service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.deploy.config.DeployProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 登录验证服务：维护「是否需要登录」开关（运行时可切换并落盘），并提供账号密码校验。
 * 账号密码取自 {@link DeployProperties.Auth}（默认 admin / Admin123，可在 application.yml 覆盖）；
 * 开关状态独立持久化到 authFile，重启后保持。
 */
@Component
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final DeployProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final File file;
    private volatile boolean enabled;
    private final Object lock = new Object();

    public AuthService(DeployProperties properties) {
        this.properties = properties;
        this.file = new File(properties.getAuthFile());
        this.enabled = properties.getAuth().isEnabled();
    }

    /** 免密模式：登录开关恒为关闭，忽略 yml 与 auth.json 的历史状态 */
    private static final boolean AUTH_DISABLED = true;

    @PostConstruct
    public void init() {
        if (AUTH_DISABLED) {
            enabled = false;
            return;
        }
        if (!file.exists()) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (json.trim().length() == 0) {
                return;
            }
            Map<String, Object> m = mapper.readValue(json, Map.class);
            if (m.get("enabled") instanceof Boolean) {
                enabled = (Boolean) m.get("enabled");
            }
        } catch (Exception e) {
            log.warn("读取登录开关配置失败: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getUsername() {
        return properties.getAuth().getUsername();
    }

    /** 校验账号密码（写死在配置中的 admin / Admin123） */
    public boolean authenticate(String username, String password) {
        String u = properties.getAuth().getUsername();
        String p = properties.getAuth().getPassword();
        return u != null && u.equals(username) && p != null && p.equals(password);
    }

    /** 切换登录开关并持久化（免密模式下忽略，恒为关闭） */
    public void setEnabled(boolean enabled) {
        if (AUTH_DISABLED) {
            this.enabled = false;
            return;
        }
        synchronized (lock) {
            this.enabled = enabled;
            persist();
        }
    }

    public Map<String, Object> status(boolean loggedIn) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("enabled", enabled);
        m.put("loggedIn", loggedIn);
        m.put("username", getUsername());
        return m;
    }

    private void persist() {
        try {
            Path path = file.toPath().toAbsolutePath();
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            String json = mapper.writeValueAsString(Collections.singletonMap("enabled", enabled));
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("保存登录开关配置失败: {}", e.getMessage(), e);
        }
    }
}
