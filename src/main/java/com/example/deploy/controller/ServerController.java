package com.example.deploy.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.deploy.model.DeployTarget;
import com.example.deploy.model.R;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.service.DeployService;
import com.example.deploy.service.DynamicCodeStore;
import com.example.deploy.service.OperationLogService;
import com.example.deploy.store.DeployTargetStore;
import com.example.deploy.store.ServerConfigStore;
import com.example.deploy.store.ServerGroupStore;
import com.example.deploy.util.ShellUtil;

/**
 * 服务器（账号密码等连接信息）配置管理接口。
 * 发布相关配置（目录 / jar / 启动参数）独立维护，见 DeployTargetController。
 */
@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerConfigStore store;
    private final DeployTargetStore targetStore;
    private final ServerGroupStore groupStore;
    private final DeployService deployService;
    private final OperationLogService operationLogService;
    private final DynamicCodeStore dynamicCodeStore;

    /** 导入解析用：忽略未知字段，兼容旧导出文件中已抽离到发布配置的字段 */
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ServerController(ServerConfigStore store, DeployTargetStore targetStore, ServerGroupStore groupStore,
                            DeployService deployService, OperationLogService operationLogService,
                            DynamicCodeStore dynamicCodeStore) {
        this.store = store;
        this.targetStore = targetStore;
        this.groupStore = groupStore;
        this.deployService = deployService;
        this.operationLogService = operationLogService;
        this.dynamicCodeStore = dynamicCodeStore;
    }

    @GetMapping
    public R<List<ServerConfig>> list() {
        return R.ok(store.listSafe());
    }

    @GetMapping("/{id}")
    public R<ServerConfig> detail(@PathVariable("id") String id) {
        ServerConfig config = store.getSafe(id);
        if (config == null) {
            return R.fail("服务器配置不存在");
        }
        return R.ok(config);
    }

    /** 新增或修改（带 id 为修改；修改时密码留空表示不变）。新增时自动创建默认发布配置 */
    @PostMapping
    public R<ServerConfig> save(@RequestBody ServerConfig config) {
        boolean isUpdate = config.getId() != null && store.getSafe(config.getId()) != null;
        String action = isUpdate ? "SERVER_UPDATE" : "SERVER_ADD";
        long begin = System.currentTimeMillis();
        try {
            ServerConfig saved = store.save(config);
            // 密码改动或关闭动态码后，之前输入的动态码不再适用：清掉，下次连接重新提示输入
            if (ShellUtil.isNotBlank(config.getPassword()) || !saved.isDynamicCodeEnabled()) {
                dynamicCodeStore.clear(saved.getId());
            }
            // 所属文件夹若未注册，自动注册，保证下拉框/侧边栏一致
            if (saved.getGroup() != null && !saved.getGroup().trim().isEmpty()) {
                groupStore.addIfAbsent(saved.getGroup().trim());
            }
            if (!isUpdate) {
                // 新服务器自动带一个默认发布配置，保证发布入口可用（字段可在页面补充）
                DeployTarget t = targetStore.ensureDefault(saved.getId(), saved.getName());
                operationLogService.record(saved, action, true, System.currentTimeMillis() - begin,
                        config.getHost() + ":" + config.effectivePort() + "（含默认发布配置「" + t.getName() + "」）");
            } else {
                operationLogService.record(saved, action, true, System.currentTimeMillis() - begin,
                        config.getHost() + ":" + config.effectivePort());
            }
            return R.ok(saved);
        } catch (Exception e) {
            operationLogService.record(config, action, false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /**
     * 修改服务器所属文件夹（拖拽到文件夹 / 取消分组）。
     * 入参 { "group": "文件夹名" }，空白字符串或 null 表示「未分组」。
     */
    @PostMapping("/{id}/group")
    public R<ServerConfig> updateGroup(@PathVariable("id") String id,
                                       @RequestBody Map<String, String> body) {
        String group = body == null ? null : body.get("group");
        long begin = System.currentTimeMillis();
        try {
            ServerConfig saved = store.setGroup(id, group);
            if (saved.getGroup() != null && !saved.getGroup().trim().isEmpty()) {
                groupStore.addIfAbsent(saved.getGroup().trim());
            }
            operationLogService.record(saved, "SERVER_GROUP", true, System.currentTimeMillis() - begin,
                    "移动到分组「" + (group == null ? "未分组" : group) + "」");
            return R.ok(saved);
        } catch (Exception e) {
            operationLogService.record(null, "SERVER_GROUP", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") String id) {
        ServerConfig existing = store.getSafe(id);
        String name = existing == null ? id : existing.getName();
        long begin = System.currentTimeMillis();
        try {
            store.delete(id);
            dynamicCodeStore.clear(id);
            // 级联删除该服务器下的全部发布配置
            targetStore.deleteByServer(id);
            operationLogService.record(existing, "SERVER_DELETE", true, System.currentTimeMillis() - begin, name);
            return R.ok();
        } catch (Exception e) {
            operationLogService.record(existing, "SERVER_DELETE", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /** 测试账号密码是否能登录，并检查远端 java 环境 */
    @PostMapping("/{id}/test")
    public R<Map<String, Object>> test(@PathVariable("id") String id) {
        return R.ok(deployService.testConnection(id));
    }

    /**
     * 提交登录动态码（MFA）。动态码只保存在本服务内存中，连接时拼接到密码末尾使用，
     * 不会写入 servers.json；默认 5 分钟有效，过期后前端会再次提示输入。
     */
    @PostMapping("/{id}/dynamic-code")
    public R<Map<String, Object>> submitDynamicCode(@PathVariable("id") String id,
                                                    @RequestBody Map<String, String> body) {
        ServerConfig config = store.getForConnect(id);
        if (!config.isDynamicCodeEnabled()) {
            throw new IllegalArgumentException("服务器「" + config.getName() + "」未开启登录动态码");
        }
        dynamicCodeStore.put(id, body == null ? null : body.get("code"));
        operationLogService.record(config, "DYNAMIC_CODE", true, 0, "已提交登录动态码（仅内存保存）");
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("serverId", id);
        res.put("required", Boolean.TRUE);
        res.put("valid", Boolean.TRUE);
        res.put("ttlSeconds", dynamicCodeStore.ttlSeconds());
        return R.ok(res);
    }

    /** 查询动态码状态：required=是否开启了动态码，valid=当前是否已有可用动态码 */
    @GetMapping("/{id}/dynamic-code")
    public R<Map<String, Object>> dynamicCodeStatus(@PathVariable("id") String id) {
        ServerConfig config = store.getSafe(id);
        if (config == null) {
            throw new IllegalArgumentException("服务器配置不存在");
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("serverId", id);
        res.put("serverName", config.getName());
        res.put("required", Boolean.valueOf(config.isDynamicCodeEnabled()));
        res.put("valid", Boolean.valueOf(config.isDynamicCodeEnabled() && dynamicCodeStore.hasValid(id)));
        res.put("ttlSeconds", dynamicCodeStore.ttlSeconds());
        return R.ok(res);
    }

    /**
     * 复制服务器：克隆连接配置，并将其下全部发布配置一并复制到一个新服务器。
     * 新服务器与发布配置名称均加「（副本）」后缀，密码等凭据原样保留（解密后重新加密）。
     */
    @PostMapping("/{id}/clone")
    public R<ServerConfig> cloneServer(@PathVariable("id") String id) {
        ServerConfig src = store.getForConnect(id); // 取解密后的凭据用于克隆
        ServerConfig newServer = src.copy();
        newServer.setId(null);
        String baseName = src.getName() == null ? "服务器" : src.getName();
        newServer.setName(baseName + "（副本）");
        long begin = System.currentTimeMillis();
        try {
            ServerConfig saved = store.save(newServer);
            if (saved.getGroup() != null && !saved.getGroup().trim().isEmpty()) {
                groupStore.addIfAbsent(saved.getGroup().trim());
            }
            int copied = 0;
            for (DeployTarget t : targetStore.listByServer(id)) {
                DeployTarget c = t.copy();
                c.setId(null);
                c.setServerId(saved.getId());
                c.setName((t.getName() == null ? "发布配置" : t.getName()) + "（副本）");
                targetStore.save(c);
                copied++;
            }
            operationLogService.record(saved, "SERVER_CLONE", true, System.currentTimeMillis() - begin,
                    "复制自「" + baseName + "」" + (copied > 0 ? "（含 " + copied + " 个发布配置）" : ""));
            return R.ok(saved);
        } catch (Exception e) {
            operationLogService.record(src, "SERVER_CLONE", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /**
     * 导出全部配置为 JSON（由前端触发浏览器下载）：
     * 服务器连接配置（密码以 ENC: 密文随出，可直接再次导入还原）+ 每台服务器的全部发布配置 + 文件夹列表。
     * 旧版本只导出服务器连接信息，导致跨机器导入后发布配置（jar 目录 / jar 名称等）丢失，此版本一并打包。
     */
    @GetMapping("/export")
    public R<Map<String, Object>> exportAll() {
        long begin = System.currentTimeMillis();
        try {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            List<ServerConfig> servers = store.listRaw();
            List<DeployTarget> targets = targetStore.listRaw();
            List<String> groups = groupStore.list();
            data.put("version", 2);
            data.put("exportTime", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            data.put("servers", servers);
            data.put("targets", targets);
            data.put("groups", groups);
            operationLogService.record(null, "EXPORT", true, System.currentTimeMillis() - begin,
                    "导出 " + servers.size() + " 台服务器 / " + targets.size() + " 个发布配置 / " + groups.size() + " 个文件夹");
            return R.ok(data);
        } catch (Exception e) {
            operationLogService.record(null, "EXPORT", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /**
     * 导入配置。支持两种格式：
     * <ul>
     *   <li>v2 导出文件：{@code { "servers": [...], "targets": [...], "groups": [...] }}，
     *       发布配置与文件夹随服务器一并还原（服务器 id 按映射转换）；</li>
     *   <li>旧版纯数组：{@code [ ServerConfig, ... ]}，服务器条目里的旧发布字段
     *       （remoteDir / jarName / jvmOpts 等）自动迁移为该服务器的发布配置。</li>
     * </ul>
     * 服务器按 id（其次 名称+host+port）合并：已存在则更新，不存在则新增；
     * 同一台主机上的多个应用（多条配置）不再被互相吞并。
     */
    @PostMapping("/import")
    public R<Map<String, Object>> importAll(@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        long begin = System.currentTimeMillis();
        try {
            List<ServerConfig> configs = new ArrayList<ServerConfig>();
            List<DeployTarget> targets = new ArrayList<DeployTarget>();
            List<String> groups = null;
            boolean legacyFormat = false;
            if (body != null && body.isArray()) {
                // 旧版纯数组格式
                legacyFormat = true;
                for (com.fasterxml.jackson.databind.JsonNode node : body) {
                    if (node == null || !node.isObject()) {
                        continue;
                    }
                    configs.add(mapper.convertValue(node, ServerConfig.class));
                    DeployTarget legacy = legacyTargetOf(node);
                    if (legacy != null) {
                        targets.add(legacy);
                    }
                }
            } else if (body != null && body.isObject()) {
                com.fasterxml.jackson.databind.JsonNode serversNode = body.path("servers");
                if (!serversNode.isArray()) {
                    throw new IllegalArgumentException("文件格式不正确：应为服务器配置数组，或包含 servers 字段的导出文件");
                }
                configs = mapper.convertValue(serversNode,
                        new com.fasterxml.jackson.core.type.TypeReference<List<ServerConfig>>() {
                        });
                com.fasterxml.jackson.databind.JsonNode targetsNode = body.path("targets");
                if (targetsNode.isArray()) {
                    targets = mapper.convertValue(targetsNode,
                            new com.fasterxml.jackson.core.type.TypeReference<List<DeployTarget>>() {
                            });
                }
                com.fasterxml.jackson.databind.JsonNode groupsNode = body.path("groups");
                if (groupsNode.isArray()) {
                    groups = mapper.convertValue(groupsNode,
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                            });
                }
            } else {
                throw new IllegalArgumentException("文件内容为空");
            }

            // 1) 导入服务器，记录 旧id → 新id 映射与逐条最终 id
            Map<String, String> idMapping = new LinkedHashMap<String, String>();
            List<String> finalIds = new ArrayList<String>();
            Map<String, Object> res = store.importServers(configs, idMapping, finalIds);

            // 2) 注册文件夹（v2 显式导出的顺序在前；服务器条目里的 group 兜底注册）
            if (groups != null) {
                for (String g : groups) {
                    try {
                        if (g != null && !g.trim().isEmpty()) {
                            groupStore.addIfAbsent(g.trim());
                        }
                    } catch (Exception e) {
                        logImportWarning("文件夹「" + g + "」注册失败: " + e.getMessage());
                    }
                }
            }
            for (ServerConfig c : configs) {
                if (c != null && c.getGroup() != null && !c.getGroup().trim().isEmpty()) {
                    try {
                        groupStore.addIfAbsent(c.getGroup().trim());
                    } catch (Exception e) {
                        logImportWarning("文件夹「" + c.getGroup() + "」注册失败: " + e.getMessage());
                    }
                }
            }

            // 3) 还原发布配置：旧格式按数组位置关联服务器，v2 按 id 映射关联
            @SuppressWarnings("unchecked")
            List<String> errors = (List<String>) res.get("errors");
            if (errors == null) {
                errors = new ArrayList<String>();
            }
            List<DeployTarget> targetsToImport = targets;
            if (legacyFormat) {
                // 旧格式数组：targets 与 configs 同序，serverId 改为对应导入后的最终 id
                targetsToImport = new ArrayList<DeployTarget>();
                for (int i = 0; i < targets.size(); i++) {
                    DeployTarget t = targets.get(i);
                    String fid = i < finalIds.size() ? finalIds.get(i) : null;
                    if (fid == null) {
                        errors.add((t.getName() != null ? t.getName() : "发布配置") + ": 所属服务器导入失败，跳过");
                        continue;
                    }
                    DeployTarget copy = t.copy();
                    copy.setServerId(fid);
                    targetsToImport.add(copy);
                }
            }
            Map<String, Object> targetRes = targetStore.importTargets(targetsToImport, idMapping, errors);
            int targetCount = targetRes.get("imported") instanceof Integer ? (Integer) targetRes.get("imported") : 0;

            // 4) 没有任何发布配置的服务器兜底创建默认发布配置，保证发布入口可用
            for (String id : finalIds) {
                if (id != null) {
                    ServerConfig s = store.getSafe(id);
                    targetStore.ensureDefault(id, s == null ? null : s.getName());
                }
            }

            res.put("targets", targetCount);
            res.put("errors", errors);
            int errCount = errors.size();
            operationLogService.record(null, "IMPORT", true, System.currentTimeMillis() - begin,
                    "新增 " + res.get("added") + " / 更新 " + res.get("updated")
                            + " / 发布配置 " + targetCount + " / 失败 " + errCount);
            return R.ok(res);
        } catch (Exception e) {
            operationLogService.record(null, "IMPORT", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    private void logImportWarning(String message) {
        // 导入过程中的可忽略告警（如文件夹名称非法），不影响整体导入结果
        org.slf4j.LoggerFactory.getLogger(ServerController.class).warn("导入配置告警: {}", message);
    }

    /**
     * 旧版纯数组格式：服务器条目里的发布字段（jarName 等）迁移为发布配置。
     * 字段含义与 DataMigration 的启动迁移逻辑保持一致。
     */
    private DeployTarget legacyTargetOf(com.fasterxml.jackson.databind.JsonNode node) {
        String jarName = node.path("jarName").asText("");
        if (jarName.trim().isEmpty()) {
            return null;
        }
        DeployTarget t = new DeployTarget();
        t.setServerId(node.path("id").asText(null));
        t.setName(legacyTargetName(jarName, node.path("name").asText("")));
        t.setRemoteDir(node.path("remoteDir").asText(null));
        t.setJarName(jarName);
        t.setJavaCmd(node.path("javaCmd").asText("java"));
        t.setJvmOpts(node.path("jvmOpts").asText(null));
        t.setAppArgs(node.path("appArgs").asText(null));
        t.setInfoLogFile(node.path("logFile").asText(null));
        t.setPidFile(node.path("pidFile").asText(null));
        t.setBackupDir(node.path("backupDir").asText(null));
        t.setPreStartCmd(node.path("preStartCmd").asText(null));
        t.setPostStartCmd(node.path("postStartCmd").asText(null));
        return t;
    }

    private static String legacyTargetName(String jarName, String serverName) {
        String name = jarName.trim();
        int i = name.toLowerCase().lastIndexOf(".jar");
        if (i > 0) {
            name = name.substring(0, i);
        }
        if (!name.trim().isEmpty()) {
            return name;
        }
        return serverName == null || serverName.trim().isEmpty() ? "默认发布配置" : serverName;
    }
}
