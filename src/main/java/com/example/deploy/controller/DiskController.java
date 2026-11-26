package com.example.deploy.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.deploy.model.DeployTask;
import com.example.deploy.model.DiskMountRequest;
import com.example.deploy.model.R;
import com.example.deploy.service.DiskService;
import com.example.deploy.store.ServerConfigStore;

/**
 * 磁盘管理：扫描服务器磁盘（fdisk -l + lsblk），并对全新未格式化磁盘执行整盘格式化挂载。
 */
@RestController
@RequestMapping("/api/disk")
public class DiskController {

    private final ServerConfigStore store;
    private final DiskService diskService;

    public DiskController(ServerConfigStore store, DiskService diskService) {
        this.store = store;
        this.diskService = diskService;
    }

    /** 扫描指定服务器的磁盘列表，返回 fdisk -l 原始输出与可挂载的新磁盘 */
    @GetMapping("/scan")
    public R<DiskService.DiskScanResult> scan(@RequestParam("serverId") String serverId) {
        if (store.getForConnect(serverId) == null) {
            return R.fail("服务器不存在");
        }
        return R.ok(diskService.scan(serverId));
    }

    /** 对选中的全新磁盘执行整盘格式化 + 挂载 + 写 fstab（异步，返回任务） */
    @PostMapping("/mount")
    public R<DeployTask> mount(@RequestBody DiskMountRequest req) {
        if (req == null || req.getServerId() == null) {
            return R.fail("缺少服务器参数");
        }
        if (req.getDisks() == null || req.getDisks().isEmpty()) {
            return R.fail("请至少选择一块磁盘");
        }
        return R.ok(diskService.mountAsync(req));
    }
}
