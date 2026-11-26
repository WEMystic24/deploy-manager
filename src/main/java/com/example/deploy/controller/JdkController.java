package com.example.deploy.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.deploy.model.R;
import com.example.deploy.service.JdkService;

/**
 * 一键部署 JDK：按服务器把本机上传的 JDK 压缩包安装到目标服务器并配置环境变量。
 *
 * <p>接口立即返回 taskId，前端用 {@code /api/deploy/tasks/{taskId}/stream}（SSE）订阅安装进度。</p>
 */
@RestController
@RequestMapping("/api/servers")
public class JdkController {

    private final JdkService jdkService;

    public JdkController(JdkService jdkService) {
        this.jdkService = jdkService;
    }

    /**
     * 上传 JDK 安装包并安装到指定服务器。
     *
     * @param serverId   目标服务器 ID（来自 URL 路径）
     * @param file       本机选中的 JDK 压缩包（tar.gz / tgz / tar / zip）
     * @param installDir 安装目录（JDK 解压到此目录下），默认 /usr/local/java
     * @param linkName   可选：在 installDir 下创建的软链接名（如 jdk8），便于多版本切换；填了就用该链接作为 JAVA_HOME
     */
    @PostMapping("/{serverId}/jdk/install")
    public R<String> install(@PathVariable("serverId") String serverId,
                             @RequestParam("file") MultipartFile file,
                             @RequestParam(value = "installDir", defaultValue = "/usr/local/java") String installDir,
                             @RequestParam(value = "linkName", required = false) String linkName) {
        return R.ok(jdkService.installAsync(serverId, file, installDir, linkName).taskId);
    }
}
