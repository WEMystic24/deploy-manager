package com.example.deploy.store;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.deploy.config.DeployProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 服务器文件夹（分组）列表的存取：内存有序列表 + JSON 文件持久化。
 * 文件夹只是一组名称，服务器通过 {@link com.example.deploy.model.ServerConfig#getGroup()} 引用。
 */
@Component
public class ServerGroupStore {

    private static final Logger log = LoggerFactory.getLogger(ServerGroupStore.class);

    private final DeployProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> groups = new CopyOnWriteArrayList<String>();

    public ServerGroupStore(DeployProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        load();
    }

    /** 有序的文件夹名称列表 */
    public List<String> list() {
        return new ArrayList<String>(groups);
    }

    public void add(String name) {
        validateName(name);
        if (groups.contains(name)) {
            throw new IllegalArgumentException("文件夹已存在：" + name);
        }
        groups.add(name);
        persist();
    }

    /** 幂等注册：不存在则添加，存在则忽略；返回是否新增 */
    public boolean addIfAbsent(String name) {
        validateName(name);
        if (groups.contains(name)) {
            return false;
        }
        groups.add(name);
        persist();
        return true;
    }

    public void rename(String oldName, String newName) {
        validateName(newName);
        if (!groups.contains(oldName)) {
            throw new IllegalArgumentException("文件夹不存在：" + oldName);
        }
        if (!oldName.equals(newName) && groups.contains(newName)) {
            throw new IllegalArgumentException("文件夹已存在：" + newName);
        }
        int idx = groups.indexOf(oldName);
        groups.set(idx, newName);
        persist();
    }

    public void delete(String name) {
        if (!groups.remove(name)) {
            throw new IllegalArgumentException("文件夹不存在：" + name);
        }
        persist();
    }

    private void validateName(String name) {
        if (name == null || name.trim().length() == 0) {
            throw new IllegalArgumentException("文件夹名称不能为空");
        }
        if (name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("文件夹名称不能包含 /");
        }
    }

    private synchronized void persist() {
        try {
            Path path = Paths.get(properties.getGroupFile()).toAbsolutePath();
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            String json = mapper.writeValueAsString(groups);
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("保存文件夹失败: {}", e.getMessage(), e);
            throw new IllegalStateException("保存文件夹失败: " + e.getMessage(), e);
        }
    }

    private void load() {
        File file = new File(properties.getGroupFile());
        if (!file.exists()) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (json.trim().length() == 0) {
                return;
            }
            List<String> list = mapper.readValue(json, new TypeReference<List<String>>() {
            });
            if (list != null) {
                groups.addAll(list);
            }
        } catch (Exception e) {
            log.error("读取文件夹失败: {}", e.getMessage(), e);
        }
    }
}
