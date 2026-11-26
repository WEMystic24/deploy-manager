package com.example.deploy.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.deploy.model.DeployTarget;
import com.example.deploy.model.ServerConfig;
import com.example.deploy.store.DeployTargetStore;
import com.example.deploy.store.ServerConfigStore;
import com.example.deploy.util.ShellUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 启动时数据迁移：
 * <p>老版本把「发布目录 / jar 名称 / 启动参数」等发布信息存在服务器配置里。
 * 新版本发布信息抽离为独立发布配置（DeployTarget）。这里把旧配置中的发布字段
 * 自动迁移成每台服务器的第一个发布配置，并把服务器配置文件清理为纯连接信息。</p>
 *
 * <p>幂等：已有发布配置的服务器跳过；服务器配置已不含发布字段时直接跳过。</p>
 */
@Component
public class DataMigration {

    private static final Logger log = LoggerFactory.getLogger(DataMigration.class);

    private static final String[] LEGACY_FIELDS = {
            "remoteDir", "jarName", "javaCmd", "jvmOpts", "appArgs",
            "logFile", "pidFile", "backupDir", "preStartCmd", "postStartCmd"
    };

    private final DeployProperties properties;
    private final ServerConfigStore serverStore;
    private final DeployTargetStore targetStore;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public DataMigration(DeployProperties properties, ServerConfigStore serverStore, DeployTargetStore targetStore) {
        this.properties = properties;
        this.serverStore = serverStore;
        this.targetStore = targetStore;
    }

    @PostConstruct
    public void migrate() {
        Path path = Paths.get(properties.getDataFile()).toAbsolutePath();
        File file = path.toFile();
        if (!file.exists()) {
            return;
        }
        JsonNode arr;
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            arr = mapper.readTree(json);
        } catch (IOException e) {
            log.warn("读取旧配置失败（跳过迁移）: {}", e.getMessage());
            return;
        }
        if (!arr.isArray() || arr.size() == 0) {
            return;
        }
        boolean migrated = false;
        for (JsonNode node : arr) {
            String serverId = node.path("id").asText("");
            String jarName = node.path("jarName").asText("");
            if (ShellUtil.isBlank(serverId) || ShellUtil.isBlank(jarName)) {
                continue;
            }
            if (!targetStore.listByServer(serverId).isEmpty()) {
                continue;
            }
            DeployTarget t = new DeployTarget();
            t.setServerId(serverId);
            t.setName(defaultName(node.path("jarName").asText(""), node.path("name").asText("")));
            t.setRemoteDir(node.path("remoteDir").asText(null));
            t.setJarName(jarName);
            t.setJavaCmd(node.path("javaCmd").asText("java"));
            t.setJvmOpts(node.path("jvmOpts").asText(null));
            t.setAppArgs(node.path("appArgs").asText(null));
            t.setLogFile(node.path("logFile").asText(null));
            t.setPidFile(node.path("pidFile").asText(null));
            t.setBackupDir(node.path("backupDir").asText(null));
            t.setPreStartCmd(node.path("preStartCmd").asText(null));
            t.setPostStartCmd(node.path("postStartCmd").asText(null));
            try {
                targetStore.save(t);
                log.info("已把旧服务器配置中的发布信息迁移为发布配置「{}」", t.getName());
                migrated = true;
            } catch (Exception e) {
                log.warn("迁移服务器 {} 的发布配置失败: {}", serverId, e.getMessage());
            }
        }
        if (migrated) {
            cleanupServerFile(arr, path);
        }
    }

    /** 写回清理后的服务器配置：去掉发布字段，只保留连接信息 */
    private void cleanupServerFile(JsonNode arr, Path path) {
        try {
            List<ServerConfig> clean = new ArrayList<ServerConfig>();
            for (JsonNode node : arr) {
                clean.add(mapper.treeToValue(node, ServerConfig.class));
            }
            String json = mapper.writeValueAsString(clean);
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
            log.info("已清理服务器配置文件中的发布字段（共 {} 台）", Integer.valueOf(clean.size()));
        } catch (Exception e) {
            log.warn("清理服务器配置文件失败（可忽略，不影响使用）: {}", e.getMessage());
        }
    }

    private static String defaultName(String jarName, String serverName) {
        if (ShellUtil.isNotBlank(jarName)) {
            String name = jarName.trim();
            int i = name.toLowerCase().lastIndexOf(".jar");
            if (i > 0) {
                name = name.substring(0, i);
            }
            return name;
        }
        return ShellUtil.isNotBlank(serverName) ? serverName : "默认发布配置";
    }
}
