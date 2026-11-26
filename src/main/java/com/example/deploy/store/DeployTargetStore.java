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
import java.util.Iterator;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.deploy.config.DeployProperties;
import com.example.deploy.model.DeployTarget;
import com.example.deploy.util.ShellUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 发布配置（DeployTarget）的存取：内存 Map + JSON 文件持久化。
 * 与服务器连接配置（ServerConfigStore）解耦，一个服务器可挂多个发布配置。
 */
@Component
public class DeployTargetStore {

    private static final Logger log = LoggerFactory.getLogger(DeployTargetStore.class);

    private final DeployProperties properties;
    private final ServerConfigStore serverStore;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            // 忽略未知字段：删除模型字段（如 folderName）后，旧配置文件仍能正常加载而不报错
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, DeployTarget> cache = Collections.synchronizedMap(new LinkedHashMap<String, DeployTarget>());

    public DeployTargetStore(DeployProperties properties, ServerConfigStore serverStore) {
        this.properties = properties;
        this.serverStore = serverStore;
    }

    @PostConstruct
    public void init() {
        File file = new File(properties.getTargetFile());
        if (!file.exists()) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (json.trim().length() == 0) {
                return;
            }
            List<DeployTarget> list = mapper.readValue(json, new TypeReference<List<DeployTarget>>() {
            });
            for (DeployTarget t : list) {
                if (ShellUtil.isBlank(t.getId())) {
                    t.setId(UUID.randomUUID().toString().replace("-", ""));
                }
                cache.put(t.getId(), t);
            }
        } catch (Exception e) {
            log.error("读取发布配置文件失败: {}", e.getMessage(), e);
        }
    }

    /** 某台服务器下的全部发布配置 */
    public List<DeployTarget> listByServer(String serverId) {
        synchronized (cache) {
            List<DeployTarget> list = new ArrayList<DeployTarget>();
            for (DeployTarget t : cache.values()) {
                if (t.getServerId() != null && t.getServerId().equals(serverId)) {
                    list.add(t);
                }
            }
            return list;
        }
    }

    /** 全部发布配置（内部使用） */
    public List<DeployTarget> listRaw() {
        synchronized (cache) {
            return new ArrayList<DeployTarget>(cache.values());
        }
    }

    public DeployTarget get(String id) {
        return cache.get(id);
    }

    /**
     * 确保某台服务器至少有一个发布配置：没有时自动创建一个默认配置（字段留空，页面再补充）。
     * 用于新服务器、导入配置等场景，保证发布入口可用。
     */
    public DeployTarget ensureDefault(String serverId, String serverName) {
        List<DeployTarget> list = listByServer(serverId);
        if (!list.isEmpty()) {
            return list.get(0);
        }
        DeployTarget t = new DeployTarget();
        t.setServerId(serverId);
        t.setName(ShellUtil.isNotBlank(serverName) ? serverName : "默认发布配置");
        t.setJavaCmd("java");
        return save(t);
    }

    /** 新增或更新。校验：名称必填；发布目录（若填）须绝对路径；jar 名称（若填）须 .jar 结尾 */
    public DeployTarget save(DeployTarget input) {
        validate(input);
        DeployTarget target;
        if (ShellUtil.isBlank(input.getId()) || !cache.containsKey(input.getId())) {
            target = input.copy();
            target.setId(ShellUtil.isBlank(input.getId())
                    ? UUID.randomUUID().toString().replace("-", "") : input.getId());
            target.setCreateTime(now());
        } else {
            DeployTarget old = cache.get(input.getId());
            target = input.copy();
            target.setCreateTime(old.getCreateTime());
        }
        target.setUpdateTime(now());
        cache.put(target.getId(), target);
        persist();
        return target.copy();
    }

    public void delete(String id) {
        if (cache.remove(id) != null) {
            persist();
        }
    }

    /**
     * 标记某发布配置已成功发布：写入最近发布时间并持久化（不触发完整校验，仅更新时间字段）。
     */
    public void markDeployed(String id) {
        DeployTarget t = cache.get(id);
        if (t != null) {
            t.setLastDeployTime(now());
            persist();
        }
    }

    /**
     * 合并写入 jar 文件名列表（自定义部署上传后自动登记），不触发完整表单校验，仅更新时间字段。
     */
    public void addJarNames(String id, List<String> names) {
        if (id == null || names == null || names.isEmpty()) {
            return;
        }
        DeployTarget t = cache.get(id);
        if (t == null) {
            return;
        }
        List<String> merged = new ArrayList<String>();
        if (t.getJarNames() != null) {
            for (String n : t.getJarNames()) {
                if (ShellUtil.isNotBlank(n) && !merged.contains(n.trim())) {
                    merged.add(n.trim());
                }
            }
        }
        for (String n : names) {
            if (ShellUtil.isNotBlank(n) && !merged.contains(n.trim())) {
                merged.add(n.trim());
            }
        }
        t.setJarNames(merged);
        // 主 jar 名（默认日志名 / {jarName} 占位符 / 卡片展示的依据）为空时用列表首项补上
        if (ShellUtil.isBlank(t.getJarName()) && !merged.isEmpty()) {
            t.setJarName(merged.get(0));
        }
        t.setUpdateTime(now());
        persist();
    }

    /**
     * 调整某台服务器下发布配置的顺序。
     * 传入该服务器全部发布配置 id 的新顺序；其他服务器的配置在缓存中的位置保持不变。
     */
    public void reorder(String serverId, List<String> ids) {
        synchronized (cache) {
            List<DeployTarget> mine = listByServer(serverId);
            if (ids == null || ids.size() != mine.size()) {
                throw new IllegalArgumentException("排序后的 id 数量与发布配置数量不一致");
            }
            Map<String, DeployTarget> mineMap = new LinkedHashMap<String, DeployTarget>();
            for (DeployTarget t : mine) {
                mineMap.put(t.getId(), t);
            }
            for (String id : ids) {
                if (!mineMap.containsKey(id)) {
                    throw new IllegalArgumentException("排序列表包含未知的发布配置 id: " + id);
                }
            }
            // 重建缓存顺序：其他服务器保持原位置，本服务器按 ids 顺序插入
            LinkedHashMap<String, DeployTarget> rebuilt = new LinkedHashMap<String, DeployTarget>();
            Iterator<String> it = ids.iterator();
            for (Map.Entry<String, DeployTarget> e : cache.entrySet()) {
                DeployTarget t = e.getValue();
                if (serverId.equals(t.getServerId())) {
                    String nextId = it.next();
                    DeployTarget nt = mineMap.get(nextId);
                    rebuilt.put(nt.getId(), nt);
                } else {
                    rebuilt.put(e.getKey(), t);
                }
            }
            cache.clear();
            cache.putAll(rebuilt);
            persist();
        }
    }

    /** 删除服务器时级联删除其全部发布配置 */
    public void deleteByServer(String serverId) {
        boolean changed = false;
        synchronized (cache) {
            List<String> ids = new ArrayList<String>();
            for (DeployTarget t : cache.values()) {
                if (t.getServerId() != null && t.getServerId().equals(serverId)) {
                    ids.add(t.getId());
                }
            }
            for (String id : ids) {
                if (cache.remove(id) != null) {
                    changed = true;
                }
            }
        }
        if (changed) {
            persist();
        }
    }

    private void validate(DeployTarget t) {
        if (t == null) {
            throw new IllegalArgumentException("发布配置不能为空");
        }
        if (ShellUtil.isBlank(t.getServerId())) {
            throw new IllegalArgumentException("缺少所属服务器");
        }
        if (serverStore.getSafe(t.getServerId()) == null) {
            throw new IllegalArgumentException("所属服务器不存在");
        }
        if (ShellUtil.isBlank(t.getName())) {
            throw new IllegalArgumentException("发布配置名称不能为空");
        }
        if (ShellUtil.isNotBlank(t.getRemoteDir()) && !t.getRemoteDir().trim().startsWith("/")) {
            throw new IllegalArgumentException("发布目录必须是绝对路径，例如 /opt/app/demo");
        }
        if (ShellUtil.isNotBlank(t.getConfDir()) && !t.getConfDir().trim().startsWith("/")) {
            throw new IllegalArgumentException("conf 目录必须是绝对路径，例如 /opt/app/demo/conf（留空则默认 发布目录/conf）");
        }
        if (ShellUtil.isNotBlank(t.getJarName()) && !t.getJarName().trim().toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("jar 包名称必须以 .jar 结尾");
        }
        // 自定义部署：多 jar 列表逐个校验文件名格式
        if (t.getJarNames() != null) {
            for (String n : t.getJarNames()) {
                if (ShellUtil.isNotBlank(n) && !n.trim().toLowerCase().endsWith(".jar")) {
                    throw new IllegalArgumentException("jar 包名称必须以 .jar 结尾：" + n);
                }
            }
        }
        String type = ShellUtil.isNotBlank(t.getType()) ? t.getType().trim() : "backend";
        if ("frontend".equals(type)) {
            if (ShellUtil.isBlank(t.getFrontendPath())) {
                throw new IllegalArgumentException("前端配置的本机发布路径不能为空");
            }
        }
        // 后端类型不在此强制 jar 名称必填：允许先保存占位配置（如新增服务器时自动创建的默认发布配置），
        // jar 名称的真正必填校验放在发布前（resolveTarget），避免创建服务器即被拦截。
        // 仅做格式校验（若填了则须以 .jar 结尾），格式校验见上方通用逻辑。
    }

    private synchronized void persist() {
        try {
            Path path = Paths.get(properties.getTargetFile()).toAbsolutePath();
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            String json = mapper.writeValueAsString(listRaw());
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("保存发布配置失败: {}", e.getMessage(), e);
            throw new IllegalStateException("保存发布配置失败: " + e.getMessage(), e);
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
