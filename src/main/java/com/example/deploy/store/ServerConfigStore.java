package com.example.deploy.store;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.deploy.config.DeployProperties;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.util.CryptoUtil;
import com.example.deploy.util.ShellUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 服务器配置的存取：内存 Map + JSON 文件持久化，密码加密保存。
 */
@Component
public class ServerConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ServerConfigStore.class);

    private final DeployProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, ServerConfig> cache = Collections.synchronizedMap(new LinkedHashMap<String, ServerConfig>());

    public ServerConfigStore(DeployProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        migrateLegacy();
        loadFromFile();
        importFromYml();
        log.info("已加载 {} 台服务器配置，配置文件: {}", cache.size(), new File(properties.getDataFile()).getAbsolutePath());
    }

    /**
     * 配置升级兼容：新版本默认把配置存到用户主目录下的绝对路径。
     * 若新路径不存在、而旧相对路径 ./data/servers.json 存在，则复制过来，避免历史配置“消失”。
     */
    private void migrateLegacy() {
        Path target = Paths.get(properties.getDataFile()).toAbsolutePath();
        if (Files.exists(target)) {
            return;
        }
        Path legacy = Paths.get("./data/servers.json").toAbsolutePath();
        if (!Files.exists(legacy)) {
            return;
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(legacy, target);
            log.info("已将旧配置从 {} 迁移到 {}", legacy, target);
        } catch (IOException e) {
            log.warn("迁移旧配置失败（可忽略，不影响新配置）: {}", e.getMessage());
        }
    }

    private void loadFromFile() {
        File file = new File(properties.getDataFile());
        if (!file.exists()) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (json.trim().length() == 0) {
                return;
            }
            List<ServerConfig> list = mapper.readValue(json, new TypeReference<List<ServerConfig>>() {
            });
            for (ServerConfig c : list) {
                if (ShellUtil.isBlank(c.getId())) {
                    c.setId(UUID.randomUUID().toString().replace("-", ""));
                }
                cache.put(c.getId(), c);
            }
        } catch (Exception e) {
            log.error("读取服务器配置文件失败: {}", e.getMessage(), e);
        }
    }

    /** yml 中预置的服务器（按 名称+host+port 去重）合并进来 */
    private void importFromYml() {
        List<ServerConfig> presets = properties.getServers();
        if (presets == null || presets.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (ServerConfig preset : presets) {
            if (ShellUtil.isBlank(preset.getHost())) {
                continue;
            }
            if (findSame(preset) != null) {
                continue;
            }
            ServerConfig c = preset.copy();
            c.setId(ShellUtil.isNotBlank(c.getId()) ? c.getId() : UUID.randomUUID().toString().replace("-", ""));
            c.setCreateTime(now());
            c.setUpdateTime(now());
            c.setPassword(CryptoUtil.encrypt(c.getPassword(), properties.getSecretKey()));
            c.setSuPassword(CryptoUtil.encrypt(c.getSuPassword(), properties.getSecretKey()));
            cache.put(c.getId(), c);
            changed = true;
        }
        if (changed) {
            persist();
        }
    }

    /** yml 预置与现有配置去重：同 id，或同 名称+host+port 视为同一台（同主机跑多个应用互不吞并） */
    private ServerConfig findSame(ServerConfig preset) {
        synchronized (cache) {
            for (ServerConfig c : cache.values()) {
                if (ShellUtil.isNotBlank(preset.getId()) && eq(c.getId(), preset.getId())) {
                    return c;
                }
                if (eq(c.getHost(), preset.getHost())
                        && c.effectivePort() == preset.effectivePort()
                        && eq(trimToNull(c.getName()), trimToNull(preset.getName()))) {
                    return c;
                }
            }
        }
        return null;
    }

    /** 按 host+port 查找已存在的配置（旧接口保留） */
    public ServerConfig findBySignature(ServerConfig preset) {
        synchronized (cache) {
            for (ServerConfig c : cache.values()) {
                if (eq(c.getHost(), preset.getHost())
                        && c.effectivePort() == preset.effectivePort()) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * 导入时查找应合并到的已有配置：优先同 id；其次同 名称+host+port。
     * 不能只用 host+port：同一台主机上往往跑多个应用（多条配置），
     * 仅按主机合并会把它们吞成一条，导致导入后服务器数量与文件夹归属错乱。
     */
    private ServerConfig findForImport(ServerConfig c) {
        synchronized (cache) {
            if (ShellUtil.isNotBlank(c.getId()) && cache.containsKey(c.getId())) {
                return cache.get(c.getId());
            }
            for (ServerConfig x : cache.values()) {
                if (eq(x.getHost(), c.getHost())
                        && x.effectivePort() == c.effectivePort()
                        && eq(trimToNull(x.getName()), trimToNull(c.getName()))) {
                    return x;
                }
            }
        }
        return null;
    }

    /**
     * 批量导入配置：按 id（其次 名称+host+port）合并，已存在则更新，否则新增（保留原 id，便于关联发布配置）。
     * 单条失败不影响其余条目，失败信息汇总到 errors。
     *
     * @param idMapping 出参（可为 null）：导出文件中的旧 id → 导入后的最终 id，用于还原发布配置的归属
     * @param finalIds  出参（可为 null）：与入参 configs 顺序一致的最终 id 列表（失败项为 null），用于旧格式按位置关联
     */
    public Map<String, Object> importServers(List<ServerConfig> configs,
                                             Map<String, String> idMapping,
                                             List<String> finalIds) {
        int added = 0;
        int updated = 0;
        List<String> errors = new ArrayList<String>();
        if (configs != null) {
            for (ServerConfig c : configs) {
                if (c == null) {
                    if (finalIds != null) {
                        finalIds.add(null);
                    }
                    continue;
                }
                String originalId = c.getId();
                String finalId = null;
                try {
                    ServerConfig existing = findForImport(c);
                    if (existing != null) {
                        c.setId(existing.getId());
                        save(c);
                        updated++;
                    } else {
                        // 保留导出文件中的 id：新机器上发布配置可凭此 id 还原归属
                        ServerConfig saved = save(c);
                        added++;
                        finalId = saved.getId();
                    }
                    if (finalId == null) {
                        finalId = existing != null ? existing.getId() : null;
                    }
                    if (idMapping != null && ShellUtil.isNotBlank(originalId) && ShellUtil.isNotBlank(finalId)) {
                        idMapping.put(originalId, finalId);
                    }
                } catch (Exception e) {
                    errors.add((c.getName() != null ? c.getName() : "未知服务器") + ": " + e.getMessage());
                }
                if (finalIds != null) {
                    finalIds.add(finalId);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("added", added);
        result.put("updated", updated);
        result.put("errors", errors);
        return result;
    }

    /** 全部配置（含密文密码，内部使用） */
    public List<ServerConfig> listRaw() {
        synchronized (cache) {
            return new ArrayList<ServerConfig>(cache.values());
        }
    }

    /** 返回给前端的安全列表 */
    public List<ServerConfig> listSafe() {
        List<ServerConfig> list = new ArrayList<ServerConfig>();
        for (ServerConfig c : listRaw()) {
            list.add(c.toSafe());
        }
        return list;
    }

    /** 取出可直接用于 SSH 登录的配置（密码已解密） */
    public ServerConfig getForConnect(String id) {
        ServerConfig c = cache.get(id);
        if (c == null) {
            throw new IllegalArgumentException("服务器配置不存在: " + id);
        }
        ServerConfig copy = c.copy();
        copy.setPassword(CryptoUtil.decrypt(copy.getPassword(), properties.getSecretKey()));
        copy.setPassphrase(CryptoUtil.decrypt(copy.getPassphrase(), properties.getSecretKey()));
        copy.setSuPassword(CryptoUtil.decrypt(copy.getSuPassword(), properties.getSecretKey()));
        return copy;
    }

    public ServerConfig getSafe(String id) {
        ServerConfig c = cache.get(id);
        return c == null ? null : c.toSafe();
    }

    /** 新增或更新。密码留空表示沿用原密码 */
    public ServerConfig save(ServerConfig input) {
        validate(input);
        ServerConfig target;
        if (ShellUtil.isBlank(input.getId()) || !cache.containsKey(input.getId())) {
            target = input.copy();
            target.setId(ShellUtil.isBlank(input.getId())
                    ? UUID.randomUUID().toString().replace("-", "") : input.getId());
            target.setCreateTime(now());
        } else {
            ServerConfig old = cache.get(input.getId());
            target = input.copy();
            target.setCreateTime(old.getCreateTime());
            if (ShellUtil.isBlank(input.getPassword())) {
                target.setPassword(old.getPassword());
            }
            if (ShellUtil.isBlank(input.getPassphrase())) {
                target.setPassphrase(old.getPassphrase());
            }
            if (Boolean.TRUE.equals(input.getSuEnabled()) && ShellUtil.isBlank(input.getSuPassword())) {
                target.setSuPassword(old.getSuPassword());
            }
        }
        target.setUpdateTime(now());
        target.setPasswordConfigured(null);
        target.setPassword(CryptoUtil.encrypt(target.getPassword(), properties.getSecretKey()));
        target.setPassphrase(CryptoUtil.encrypt(target.getPassphrase(), properties.getSecretKey()));
        target.setSuPassword(CryptoUtil.encrypt(target.getSuPassword(), properties.getSecretKey()));
        cache.put(target.getId(), target);
        persist();
        return target.toSafe();
    }

    public void delete(String id) {
        if (cache.remove(id) != null) {
            persist();
        }
    }

    /** 重命名某文件夹：把所有属于该文件夹的服务器改为新名称 */
    public void renameGroup(String oldName, String newName) {
        boolean changed = false;
        synchronized (cache) {
            for (ServerConfig c : cache.values()) {
                if (eq(c.getGroup(), oldName)) {
                    c.setGroup(newName);
                    changed = true;
                }
            }
        }
        if (changed) {
            persist();
        }
    }

    /** 修改某服务器所属的文件夹（group）；空白字符串 / null 表示「未分组」 */
    public ServerConfig setGroup(String id, String group) {
        ServerConfig c = cache.get(id);
        if (c == null) {
            throw new IllegalArgumentException("服务器配置不存在: " + id);
        }
        ServerConfig target = c.copy();
        target.setGroup(ShellUtil.isNotBlank(group) ? group.trim() : null);
        target.setUpdateTime(now());
        // 保留已加密的敏感字段，避免重复加密
        target.setPassword(c.getPassword());
        target.setPassphrase(c.getPassphrase());
        target.setSuPassword(c.getSuPassword());
        cache.put(id, target);
        persist();
        return target.toSafe();
    }

    /** 删除某文件夹：把属于该文件夹的服务器置为「未分组」 */
    public void clearGroup(String name) {
        boolean changed = false;
        synchronized (cache) {
            for (ServerConfig c : cache.values()) {
                if (eq(c.getGroup(), name)) {
                    c.setGroup(null);
                    changed = true;
                }
            }
        }
        if (changed) {
            persist();
        }
    }

    private void validate(ServerConfig c) {
        if (c == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        require(c.getName(), "名称");
        require(c.getHost(), "服务器地址");
        require(c.getUsername(), "登录账号");
        boolean isNew = ShellUtil.isBlank(c.getId()) || !cache.containsKey(c.getId());
        if (isNew && !c.usePrivateKey() && ShellUtil.isBlank(c.getPassword())) {
            throw new IllegalArgumentException("请填写登录密码");
        }
        if (c.usePrivateKey() && ShellUtil.isBlank(c.getPrivateKeyPath())) {
            throw new IllegalArgumentException("使用私钥登录时必须填写私钥文件路径");
        }
        if (Boolean.TRUE.equals(c.getDynamicCodeEnabled()) && c.usePrivateKey()) {
            throw new IllegalArgumentException("动态码作用于密码认证，使用私钥登录时请关闭「登录动态码」");
        }
        if (isNew && Boolean.TRUE.equals(c.getSuEnabled()) && ShellUtil.isBlank(c.getSuPassword())) {
            throw new IllegalArgumentException("启用 su 切换用户时必须填写 su 密码");
        }
    }

    private void require(String value, String field) {
        if (ShellUtil.isBlank(value)) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }

    private synchronized void persist() {
        try {
            Path path = Paths.get(properties.getDataFile()).toAbsolutePath();
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            String json = mapper.writeValueAsString(listRaw());
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("保存服务器配置失败: {}", e.getMessage(), e);
            throw new IllegalStateException("保存服务器配置失败: " + e.getMessage(), e);
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
