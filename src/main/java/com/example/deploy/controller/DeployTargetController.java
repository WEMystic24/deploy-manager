package com.example.deploy.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.deploy.model.DeployTarget;
import com.example.deploy.model.R;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.service.OperationLogService;
import com.example.deploy.store.DeployTargetStore;
import com.example.deploy.store.ServerConfigStore;

/**
 * 发布配置管理接口：一台服务器可配置多个发布目标（各自独立的目录 / jar / 启动参数）。
 * 路由挂在 {@code /api/servers/{serverId}/targets} 下，与服务器连接信息解耦。
 */
@RestController
@RequestMapping("/api/servers/{serverId}/targets")
public class DeployTargetController {

    private final DeployTargetStore targetStore;
    private final ServerConfigStore serverStore;
    private final OperationLogService operationLogService;

    public DeployTargetController(DeployTargetStore targetStore, ServerConfigStore serverStore,
                                  OperationLogService operationLogService) {
        this.targetStore = targetStore;
        this.serverStore = serverStore;
        this.operationLogService = operationLogService;
    }

    /** 某台服务器下的全部发布配置 */
    @GetMapping
    public R<List<DeployTarget>> list(@PathVariable("serverId") String serverId) {
        return R.ok(targetStore.listByServer(serverId));
    }

    /** 新增发布配置（serverId 以路径为准） */
    @PostMapping
    public R<DeployTarget> add(@PathVariable("serverId") String serverId,
                               @RequestBody DeployTarget target) {
        ensureServer(serverId);
        target.setServerId(serverId);
        long begin = System.currentTimeMillis();
        DeployTarget saved = targetStore.save(target);
        operationLogService.record(serverId, serverName(serverId), "TARGET_ADD", true,
                System.currentTimeMillis() - begin, "新增发布配置「" + saved.getName() + "」");
        return R.ok(saved);
    }

    /** 修改发布配置 */
    @PutMapping("/{targetId}")
    public R<DeployTarget> update(@PathVariable("serverId") String serverId,
                                  @PathVariable("targetId") String targetId,
                                  @RequestBody DeployTarget target) {
        ensureServer(serverId);
        DeployTarget existing = targetStore.get(targetId);
        if (existing == null) {
            throw new IllegalArgumentException("发布配置不存在");
        }
        target.setId(targetId);
        target.setServerId(serverId);
        long begin = System.currentTimeMillis();
        DeployTarget saved = targetStore.save(target);
        operationLogService.record(serverId, serverName(serverId), "TARGET_UPDATE", true,
                System.currentTimeMillis() - begin, "修改发布配置「" + saved.getName() + "」");
        return R.ok(saved);
    }

    /** 复制发布配置：基于已有配置生成一份同服务器下的新配置（名称加「（副本）」后缀，自动生成新 id） */
    @PostMapping("/{targetId}/copy")
    public R<DeployTarget> copy(@PathVariable("serverId") String serverId,
                                @PathVariable("targetId") String targetId) {
        ensureServer(serverId);
        DeployTarget existing = targetStore.get(targetId);
        if (existing == null) {
            throw new IllegalArgumentException("发布配置不存在");
        }
        DeployTarget copy = existing.copy();
        copy.setId(null); // 由 store 生成新 id，避免与源冲突
        copy.setServerId(serverId);
        String baseName = existing.getName() == null ? "发布配置" : existing.getName();
        copy.setName(baseName + "（副本）");
        long begin = System.currentTimeMillis();
        DeployTarget saved = targetStore.save(copy);
        operationLogService.record(serverId, serverName(serverId), "TARGET_COPY", true,
                System.currentTimeMillis() - begin,
                "复制发布配置「" + existing.getName() + "」→「" + saved.getName() + "」");
        return R.ok(saved);
    }

    /**
     * 复制发布配置到其它服务器：基于已有配置生成一份「目标服务器」下的新配置（保留原名称，serverId 改为目标服务器）。
     * 与同服务器复制（{@code /copy}）区分：用于把同一发布配置快速部署到另一台机器。
     */
    @PostMapping("/{targetId}/copy-to/{toServerId}")
    public R<DeployTarget> copyTo(@PathVariable("serverId") String serverId,
                                  @PathVariable("targetId") String targetId,
                                  @PathVariable("toServerId") String toServerId) {
        ensureServer(serverId);
        if (serverStore.getSafe(toServerId) == null) {
            throw new IllegalArgumentException("目标服务器不存在");
        }
        DeployTarget existing = targetStore.get(targetId);
        if (existing == null) {
            throw new IllegalArgumentException("发布配置不存在");
        }
        DeployTarget copy = existing.copy();
        copy.setId(null);
        copy.setServerId(toServerId);
        long begin = System.currentTimeMillis();
        DeployTarget saved = targetStore.save(copy);
        operationLogService.record(toServerId, serverName(toServerId), "TARGET_COPY_TO", true,
                System.currentTimeMillis() - begin,
                "复制「" + (existing.getName() == null ? targetId : existing.getName()) + "」到本服务器");
        return R.ok(saved);
    }

    /** 调整某台服务器下发布配置的顺序（传入该服务器全部 id 的新顺序数组） */
    @PostMapping("/reorder")
    public R<Void> reorder(@PathVariable("serverId") String serverId,
                           @RequestBody Map<String, List<String>> body) {
        ensureServer(serverId);
        List<String> ids = body == null ? null : body.get("ids");
        long begin = System.currentTimeMillis();
        try {
            targetStore.reorder(serverId, ids);
            operationLogService.record(serverId, serverName(serverId), "TARGET_REORDER", true,
                    System.currentTimeMillis() - begin,
                    "调整发布配置顺序（" + (ids == null ? 0 : ids.size()) + " 项）");
            return R.ok();
        } catch (Exception e) {
            operationLogService.record(serverId, serverName(serverId), "TARGET_REORDER", false,
                    System.currentTimeMillis() - begin, e.getMessage());
            throw e;
        }
    }

    /**
     * 批量修改发布目录（前缀替换）：对勾选的发布配置，把以 {@code oldPrefix} 开头的发布目录
     * 整体替换为 {@code newPrefix}，保留各自子路径。例如 /opt/app/order → /data/app/order。
     * 仅替换命中前缀的项，其余不受影响；替换后复用 save 落盘并校验。
     */
    @PostMapping("/batch-remote-dir")
    public R<Map<String, Object>> batchUpdateRemoteDir(@PathVariable("serverId") String serverId,
                                                       @RequestBody Map<String, Object> body) {
        ensureServer(serverId);
        List<?> rawIds = body == null ? null : (List<?>) body.get("ids");
        String oldPrefix = body == null || body.get("oldPrefix") == null ? "" : String.valueOf(body.get("oldPrefix"));
        String newPrefix = body == null || body.get("newPrefix") == null ? "" : String.valueOf(body.get("newPrefix"));
        if (rawIds == null || rawIds.isEmpty()) {
            throw new IllegalArgumentException("请先勾选要修改的发布配置");
        }
        if (oldPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("原前缀不能为空");
        }
        List<String> ids = new ArrayList<String>();
        for (Object o : rawIds) {
            ids.add(String.valueOf(o));
        }
        int changed = 0;
        List<String> affected = new ArrayList<String>();
        long begin = System.currentTimeMillis();
        for (String id : ids) {
            DeployTarget t = targetStore.get(id);
            if (t == null || !serverId.equals(t.getServerId())) {
                continue;
            }
            String dir = t.getRemoteDir();
            if (dir == null || !dir.startsWith(oldPrefix)) {
                continue;
            }
            String newDir = newPrefix + dir.substring(oldPrefix.length());
            t.setRemoteDir(newDir);
            // conf 目录同样按前缀替换（仅在显式配置且命中前缀时），避免迁移目录后 conf 仍指向旧位置
            String conf = t.getConfDir();
            if (conf != null && !conf.trim().isEmpty() && conf.trim().startsWith(oldPrefix)) {
                t.setConfDir(newPrefix + conf.trim().substring(oldPrefix.length()));
            }
            targetStore.save(t);
            changed++;
            affected.add(t.getName() + "：" + dir + " → " + newDir);
        }
        operationLogService.record(serverId, serverName(serverId), "TARGET_BATCH_DIR", changed > 0,
                System.currentTimeMillis() - begin,
                "批量修改发布目录前缀「" + oldPrefix + "」→「" + newPrefix + "」，命中 " + changed + " 项");
        Map<String, Object> res = new HashMap<String, Object>();
        res.put("changed", changed);
        res.put("affected", affected);
        return R.ok(res);
    }

    /**
     * 批量修改 JVM 参数：对勾选的「后端」类型发布配置，按指定方式设置 JVM 参数。
     * mode=overwrite 时直接覆盖为 jvmOpts（允许为空，即清空该参数）；mode=append 时追加到现有 JVM 参数末尾（用空格分隔）。
     * 前端类型配置不涉及 JVM，会被跳过（计入 skipped）；仅当合并后内容与原值不同才真正落盘并计入修改。
     */
    @PostMapping("/batch-jvm")
    public R<Map<String, Object>> batchUpdateJvm(@PathVariable("serverId") String serverId,
                                                 @RequestBody Map<String, Object> body) {
        ensureServer(serverId);
        List<?> rawIds = body == null ? null : (List<?>) body.get("ids");
        String jvmOpts = body == null || body.get("jvmOpts") == null ? "" : String.valueOf(body.get("jvmOpts")).trim();
        String mode = body == null || body.get("mode") == null ? "overwrite" : String.valueOf(body.get("mode")).trim();
        if (rawIds == null || rawIds.isEmpty()) {
            throw new IllegalArgumentException("请先勾选要修改的发布配置");
        }
        List<String> ids = new ArrayList<String>();
        for (Object o : rawIds) {
            ids.add(String.valueOf(o));
        }
        int changed = 0;
        int skipped = 0;
        List<String> affected = new ArrayList<String>();
        long begin = System.currentTimeMillis();
        for (String id : ids) {
            DeployTarget t = targetStore.get(id);
            if (t == null || !serverId.equals(t.getServerId())) {
                continue;
            }
            String type = t.getType() == null ? "backend" : t.getType();
            if (!"backend".equals(type)) {
                skipped++;
                continue;
            }
            String old = t.getJvmOpts() == null ? "" : t.getJvmOpts();
            String merged;
            if ("append".equals(mode)) {
                merged = (old.trim().isEmpty() ? "" : old.trim() + " ") + jvmOpts;
            } else {
                merged = jvmOpts;
            }
            // 合并后与原值一致（如追加空串、或覆盖成相同内容）则不重复落盘
            if (merged.equals(old)) {
                continue;
            }
            t.setJvmOpts(merged);
            targetStore.save(t);
            changed++;
            affected.add(t.getName() + "：" + (old.isEmpty() ? "(空)" : old) + " → " + merged);
        }
        operationLogService.record(serverId, serverName(serverId), "TARGET_BATCH_JVM", changed > 0,
                System.currentTimeMillis() - begin,
                "批量修改 JVM 参数（" + mode + "）：" + jvmOpts + "，命中后端配置 " + changed + " 项，跳过前端 " + skipped + " 项");
        Map<String, Object> res = new HashMap<String, Object>();
        res.put("changed", changed);
        res.put("skipped", skipped);
        res.put("affected", affected);
        return R.ok(res);
    }

    /** 删除发布配置（只删本地配置，不影响服务器文件） */
    @DeleteMapping("/{targetId}")
    public R<Void> delete(@PathVariable("serverId") String serverId,
                          @PathVariable("targetId") String targetId) {
        DeployTarget existing = targetStore.get(targetId);
        targetStore.delete(targetId);
        operationLogService.record(serverId, serverName(serverId), "TARGET_DELETE", true,
                System.currentTimeMillis() - (existing == null ? 0 : 0),
                "删除发布配置「" + (existing == null ? targetId : existing.getName()) + "」");
        return R.ok();
    }

    private void ensureServer(String serverId) {
        if (serverStore.getSafe(serverId) == null) {
            throw new IllegalArgumentException("服务器配置不存在");
        }
    }

    private String serverName(String serverId) {
        ServerConfig c = serverStore.getSafe(serverId);
        return c == null ? serverId : c.getName();
    }
}
