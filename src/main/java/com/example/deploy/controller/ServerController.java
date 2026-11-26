package com.example.deploy.controller;

import java.util.ArrayList;
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
import com.example.deploy.service.OperationLogService;
import com.example.deploy.store.DeployTargetStore;
import com.example.deploy.store.ServerConfigStore;
import com.example.deploy.store.ServerGroupStore;

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

    public ServerController(ServerConfigStore store, DeployTargetStore targetStore, ServerGroupStore groupStore,
                            DeployService deployService, OperationLogService operationLogService) {
        this.store = store;
        this.targetStore = targetStore;
        this.groupStore = groupStore;
        this.deployService = deployService;
        this.operationLogService = operationLogService;
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
     * 导出全部服务器配置为 JSON（密码以 ENC: 密文形式随出，可直接再次导入还原）。
     * 由前端触发浏览器下载。
     */
    @GetMapping("/export")
    public R<List<ServerConfig>> exportAll() {
        long begin = System.currentTimeMillis();
        try {
            List<ServerConfig> list = store.listRaw();
            operationLogService.record(null, "EXPORT", true, System.currentTimeMillis() - begin, "导出 " + list.size() + " 台服务器");
            return R.ok(list);
        } catch (Exception e) {
            operationLogService.record(null, "EXPORT", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /**
     * 从 JSON 数组导入服务器配置，按 host+port 合并：
     * 已存在则更新，不存在则新增。导入后每台服务器确保有默认发布配置。返回新增/更新数量与失败项。
     */
    @PostMapping("/import")
    public R<Map<String, Object>> importAll(@RequestBody List<ServerConfig> configs) {
        long begin = System.currentTimeMillis();
        try {
            Map<String, Object> res = store.importServers(configs);
            // 导入的服务器确保有默认发布配置（发布信息在新版本中独立维护）
            if (configs != null) {
                for (ServerConfig c : configs) {
                    if (c == null) {
                        continue;
                    }
                    ServerConfig existing = store.findBySignature(c);
                    if (existing != null) {
                        targetStore.ensureDefault(existing.getId(), existing.getName());
                    }
                }
            }
            int errCount = res.get("errors") instanceof List ? ((List<?>) res.get("errors")).size() : 0;
            operationLogService.record(null, "IMPORT", true, System.currentTimeMillis() - begin,
                    "新增 " + res.get("added") + " / 更新 " + res.get("updated") + " / 失败 " + errCount);
            return R.ok(res);
        } catch (Exception e) {
            operationLogService.record(null, "IMPORT", false, System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }
}
