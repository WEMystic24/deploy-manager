package com.example.deploy.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.example.deploy.model.R;
import com.example.deploy.store.ServerConfigStore;
import com.example.deploy.store.ServerGroupStore;

/**
 * 服务器文件夹（分组）管理接口：文件夹是一组名称，服务器通过 group 字段引用。
 */
@RestController
@RequestMapping("/api/server-groups")
public class ServerGroupController {

    private final ServerGroupStore groupStore;
    private final ServerConfigStore serverStore;

    public ServerGroupController(ServerGroupStore groupStore, ServerConfigStore serverStore) {
        this.groupStore = groupStore;
        this.serverStore = serverStore;
    }

    @GetMapping
    public R<List<String>> list() {
        return R.ok(groupStore.list());
    }

    /** 新建文件夹 */
    @PostMapping
    public R<List<String>> add(@RequestBody Map<String, String> body) {
        groupStore.add(body.get("name"));
        return R.ok(groupStore.list());
    }

    /** 重命名文件夹，并同步更新引用该文件夹的服务器 */
    @RequestMapping(value = "/rename", method = {RequestMethod.PUT, RequestMethod.POST})
    public R<List<String>> rename(@RequestBody Map<String, String> body) {
        String oldName = body.get("oldName");
        String newName = body.get("newName");
        groupStore.rename(oldName, newName);
        serverStore.renameGroup(oldName, newName);
        return R.ok(groupStore.list());
    }

    /** 删除文件夹，其下服务器自动变为「未分组」 */
    @DeleteMapping("/{name}")
    public R<List<String>> delete(@PathVariable("name") String name) {
        groupStore.delete(name);
        serverStore.clearGroup(name);
        return R.ok(groupStore.list());
    }
}
