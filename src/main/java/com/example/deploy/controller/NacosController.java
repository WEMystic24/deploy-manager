package com.example.deploy.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.deploy.model.R;
import com.example.deploy.service.NacosService;

/**
 * Nacos 集群一键部署：把本机上传的 nacos-server 安装包（tar.gz / tgz / tar / zip）分发到所选的多台
 * 服务器（复用「服务器」列表勾选），解压后生成 {@code cluster.conf}（集群节点列表）与
 * {@code application.properties}（外部数据源指向人大金仓 / Kingbase），并以集群模式启动各节点。
 *
 * <p>接口立即返回 taskId，前端用 {@code /api/deploy/tasks/{taskId}/stream}（SSE）订阅部署进度。</p>
 */
@RestController
@RequestMapping("/api/nacos")
public class NacosController {

    private final NacosService nacosService;

    public NacosController(NacosService nacosService) {
        this.nacosService = nacosService;
    }

    /**
     * 上传 Nacos 安装包并部署集群。
     *
     * @param file     本机选中的 nacos-server 压缩包
     * @param nodeIds  集群节点（服务器 id 列表，至少 2 个）
     * @param clusterName    集群名称（仅展示）
     * @param nacosPort     Nacos 服务端口（默认 8848）
     * @param installDir    Nacos 解压安装目录（默认 /opt/nacos）
     * @param kbHost        人大金仓数据库主机
     * @param kbPort        人大金仓数据库端口（默认 54321）
     * @param kbDb          数据库名（默认 nacos_config）
     * @param kbUser        数据库账号
     * @param kbPassword    数据库密码
     * @param jdbcDriver    JDBC 驱动类（默认 com.kingbase8.Driver）
     * @param jdbcUrl       完整 JDBC URL（留空则按 host/port/db 自动拼接 jdbc:kingbase8://...）
     * @param extraProps    application.properties 自定义追加项（可修改 Nacos 配置，如鉴权开关等）
     */
    @PostMapping("/deploy")
    public R<String> deploy(@RequestParam("file") MultipartFile file,
                            @RequestParam("nodeIds") List<String> nodeIds,
                            @RequestParam(value = "clusterName", defaultValue = "nacos-cluster") String clusterName,
                            @RequestParam(value = "nacosPort", defaultValue = "8848") int nacosPort,
                            @RequestParam(value = "installDir", defaultValue = "/opt/nacos") String installDir,
                            @RequestParam("kbHost") String kbHost,
                            @RequestParam(value = "kbPort", defaultValue = "54321") int kbPort,
                            @RequestParam(value = "kbDb", defaultValue = "nacos_config") String kbDb,
                            @RequestParam("kbUser") String kbUser,
                            @RequestParam("kbPassword") String kbPassword,
                            @RequestParam(value = "jdbcDriver", defaultValue = "com.kingbase8.Driver") String jdbcDriver,
                            @RequestParam(value = "jdbcUrl", required = false) String jdbcUrl,
                            @RequestParam(value = "extraProps", required = false) String extraProps) {
        return R.ok(nacosService.deployAsync(nodeIds, file, clusterName, nacosPort, installDir,
                kbHost, kbPort, kbDb, kbUser, kbPassword, jdbcDriver, jdbcUrl, extraProps).taskId);
    }
}
