package com.example.deploy.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.deploy.model.AppStatus;
import com.example.deploy.model.DeployTask;
import com.example.deploy.model.OperationLog;
import com.example.deploy.model.R;
import com.example.deploy.service.ChunkUploadService;
import com.example.deploy.service.DeployService;
import com.example.deploy.service.OperationLogService;

/**
 * 发布、启停、状态、日志、回滚接口。
 *
 * <p>发布 / 启停 / 回滚均为异步：接口立即返回 taskId，前端用
 * {@code /api/deploy/tasks/{taskId}/stream}（SSE）订阅实时进度，或用
 * {@code /api/deploy/tasks/{taskId}} 拉取当前快照。</p>
 */
@RestController
@RequestMapping("/api")
public class DeployController {

    private final DeployService deployService;
    private final OperationLogService operationLogService;
    private final ChunkUploadService chunkUploadService;

    public DeployController(DeployService deployService, OperationLogService operationLogService,
                            ChunkUploadService chunkUploadService) {
        this.deployService = deployService;
        this.operationLogService = operationLogService;
        this.chunkUploadService = chunkUploadService;
    }

    /**
     * 上传 jar 并发布（restart=true 时上传后自动重启），返回 taskId。
     * {@code targetId} 为发布配置 id：一个服务器可挂多个发布配置，分别发布不同的 jar 包。
     */
    @PostMapping("/deploy/{targetId}/upload")
    public R<String> deployUpload(@PathVariable("targetId") String targetId,
                                  @RequestParam("file") MultipartFile file,
                                  @RequestParam(value = "restart", defaultValue = "true") boolean restart) {
        return R.ok(deployService.deployByUploadAsync(targetId, file, restart).taskId);
    }

    /**
     * 一次上传多个 jar 包并统一发布（自定义部署）：所有 jar 一次性传输到发布目录后只重启一次，返回 taskId。
     * 默认部署仅支持单个 jar，多文件时会提示切换为自定义部署。
     */
    @PostMapping("/deploy/{targetId}/upload-multi")
    public R<String> deployUploadMulti(@PathVariable("targetId") String targetId,
                                       @RequestParam("files") List<MultipartFile> files,
                                       @RequestParam(value = "restart", defaultValue = "true") boolean restart) {
        return R.ok(deployService.deployByUploadsAsync(targetId, files, restart).taskId);
    }

    /**
     * 分片上传（支持断点续传）。三种 action：
     * <ul>
     *   <li>upload：接收一片（MultipartFile=file，参数 chunkIndex/totalChunks/uploadId）</li>
     *   <li>status：返回已收分片序号数组（前端据此跳过已传分片）</li>
     *   <li>finish：合并全部分片为完整 jar，并复用本地路径发布流程（备份/重启等），返回 taskId</li>
     * </ul>
     */
    @PostMapping("/deploy/{targetId}/chunk")
    public R<Object> deployChunk(@PathVariable("targetId") String targetId,
                                 @RequestParam("action") String action,
                                 @RequestParam(value = "uploadId", required = false) String uploadId,
                                 @RequestParam(value = "chunkIndex", defaultValue = "-1") int chunkIndex,
                                 @RequestParam(value = "totalChunks", defaultValue = "-1") int totalChunks,
                                 @RequestParam(value = "fileName", required = false) String fileName,
                                 @RequestParam(value = "restart", defaultValue = "true") boolean restart,
                                 @RequestParam(value = "file", required = false) MultipartFile file) {
        if ("status".equals(action)) {
            if (uploadId == null || uploadId.isEmpty()) {
                throw new IllegalArgumentException("缺少 uploadId");
            }
            return R.ok(chunkUploadService.receivedChunks(uploadId));
        }
        if ("upload".equals(action)) {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("请选择要上传的分片");
            }
            if (uploadId == null || uploadId.isEmpty() || chunkIndex < 0 || totalChunks < 1) {
                throw new IllegalArgumentException("分片参数不完整");
            }
            try {
                chunkUploadService.saveChunk(uploadId, chunkIndex, file.getBytes());
            } catch (IOException e) {
                throw new IllegalStateException("读取分片失败: " + e.getMessage(), e);
            }
            // 返回当前已收分片，便于前端实时更新断点进度
            return R.ok(chunkUploadService.receivedChunks(uploadId));
        }
        if ("finish".equals(action)) {
            if (uploadId == null || uploadId.isEmpty() || totalChunks < 1) {
                throw new IllegalArgumentException("合并参数不完整");
            }
            if (!chunkUploadService.isComplete(uploadId, totalChunks)) {
                throw new IllegalStateException("分片未全部到齐，无法发布（已收 "
                        + chunkUploadService.receivedChunks(uploadId).size() + "/" + totalChunks + "）");
            }
            String mergedPath = chunkUploadService.merge(uploadId, totalChunks, fileName);
            // 合并产物在本服务磁盘上，按发布配置类型分流：前端类型走静态资源发布，后端类型走 jar 发布（含重启）
            return R.ok(deployService.deployFileAsync(targetId, mergedPath, restart).taskId);
        }
        throw new IllegalArgumentException("未知的 action: " + action);
    }

    /**
     * 上传 conf 文件（yml / properties 等）到发布配置的 conf 目录（同名覆盖，不重启应用）。
     * 返回 {confDir, files}：实际落地的目录与已上传的文件名列表。
     */
    @PostMapping("/deploy/{targetId}/conf-upload")
    public R<Map<String, Object>> uploadConf(@PathVariable("targetId") String targetId,
                                             @RequestParam("files") List<MultipartFile> files) {
        return R.ok(deployService.uploadConfFiles(targetId, files));
    }

    /** 用本机已有的 jar 路径发布，适合本地 Maven 打包后直接推送，返回 taskId */
    @PostMapping("/deploy/{targetId}/local")
    public R<String> deployLocal(@PathVariable("targetId") String targetId,
                                 @RequestParam("path") String path,
                                 @RequestParam(value = "restart", defaultValue = "true") boolean restart) {
        return R.ok(deployService.deployByLocalPathAsync(targetId, path, restart).taskId);
    }

    /** 前端静态资源发布：读取配置中的本机发布路径，按是否压缩 zip 上传并执行发布后命令，返回 taskId */
    @PostMapping("/deploy/{targetId}/frontend")
    public R<String> deployFrontend(@PathVariable("targetId") String targetId) {
        return R.ok(deployService.deployFrontendAsync(targetId).taskId);
    }

    /** 手动备份：后端类型备份 jar 包，前端类型备份整个发布目录，返回 taskId */
    @PostMapping("/deploy/{targetId}/backup")
    public R<String> backup(@PathVariable("targetId") String targetId) {
        return R.ok(deployService.backupAsync(targetId).taskId);
    }

    @PostMapping("/apps/{targetId}/start")
    public R<String> start(@PathVariable("targetId") String targetId) {
        return R.ok(deployService.startAsync(targetId).taskId);
    }

    @PostMapping("/apps/{targetId}/stop")
    public R<String> stop(@PathVariable("targetId") String targetId) {
        return R.ok(deployService.stopAsync(targetId).taskId);
    }

    @PostMapping("/apps/{targetId}/restart")
    public R<String> restart(@PathVariable("targetId") String targetId) {
        return R.ok(deployService.restartAsync(targetId).taskId);
    }

    /** 回滚到指定历史备份，返回 taskId */
    @PostMapping("/apps/{targetId}/rollback")
    public R<String> rollback(@PathVariable("targetId") String targetId,
                              @RequestParam("name") String name,
                              @RequestParam(value = "restart", defaultValue = "true") boolean restart) {
        return R.ok(deployService.rollbackAsync(targetId, name, restart).taskId);
    }

    @GetMapping("/apps/{targetId}/status")
    public R<AppStatus> status(@PathVariable("targetId") String targetId) {
        return R.ok(deployService.status(targetId));
    }

    @GetMapping("/apps/{targetId}/log")
    public R<String> log(@PathVariable("targetId") String targetId,
                         @RequestParam(value = "lines", defaultValue = "200") int lines,
                         @RequestParam(value = "type", defaultValue = "info") String type,
                         @RequestParam(value = "q", required = false) String q) {
        return R.ok(deployService.tailLog(targetId, type, lines, q));
    }

    @GetMapping("/apps/{targetId}/backups")
    public R<List<Map<String, String>>> backups(@PathVariable("targetId") String targetId) {
        return R.ok(deployService.listBackups(targetId));
    }

    /** 任务当前快照（供轮询 / SSE 重连兜底） */
    @GetMapping("/deploy/tasks/{taskId}")
    public R<DeployTask> task(@PathVariable("taskId") String taskId) {
        DeployTask task = deployService.getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在或已过期（页面刷新后请重新操作）");
        }
        return R.ok(task);
    }

    /** SSE 实时进度流：连接即推送当前快照，后续每步状态增量推送，结束时推送最终结果与 done 事件 */
    @GetMapping(value = "/deploy/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("taskId") String taskId) {
        DeployTask task = deployService.getTask(taskId);
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));
        if (task == null) {
            // 任务不存在（多半是刷新导致 taskId 失效）：用 conn-error 事件告知前端，避免使用浏览器保留的 error 事件名
            try {
                emitter.send(SseEmitter.event().name("conn-error").data("任务不存在或已过期，请重新执行操作"));
            } catch (IOException ignored) {
                // ignore
            }
            emitter.complete();
            return emitter;
        }
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(task));
        } catch (IOException ex) {
            emitter.complete();
            return emitter;
        }
        if (task.isFinished()) {
            try {
                task.sendFinal(emitter);
            } catch (Exception ignored) {
                emitter.complete();
            }
            return emitter;
        }
        task.emitters.add(emitter);
        emitter.onCompletion(() -> task.emitters.remove(emitter));
        emitter.onTimeout(() -> task.emitters.remove(emitter));
        // 防止“发送快照后、注册监听前”任务刚好结束而漏掉 done 事件
        if (task.isFinished()) {
            task.emitters.remove(emitter);
            try {
                task.sendFinal(emitter);
            } catch (Exception ignored) {
                emitter.complete();
            }
        }
        return emitter;
    }

    @GetMapping("/logs")
    public R<List<OperationLog>> logs(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return R.ok(operationLogService.list(limit));
    }
}
