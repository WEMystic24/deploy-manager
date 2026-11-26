package com.example.deploy.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.deploy.config.DeployProperties;

/**
 * 分片上传的落盘与合并。
 *
 * <p>前端把 jar 切成固定大小的片段逐片上传，本服务把每片写入
 * {@code <uploadDir>/chunks/<uploadId>/<index>.part}。任意一片传输中断都不影响其他片，
 * 重新上传时通过 {@link #receivedChunks(String)} 查询已收分片，只补传缺失的片段，
 * 实现浏览器侧的断点续传。全部到齐后 {@link #merge(String, int, String)} 合并为完整 jar，
 * 再交由发布流程（本服务→目标服务器的 SFTP 整文件传输）。</p>
 */
@Service
public class ChunkUploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkUploadService.class);

    /** 分片大小：5MB */
    public static final long CHUNK_SIZE = 5L * 1024 * 1024;

    private final DeployProperties properties;

    public ChunkUploadService(DeployProperties properties) {
        this.properties = properties;
    }

    private File chunkDir(String uploadId) {
        return new File(properties.getUploadDir(), "chunks/" + uploadId);
    }

    /** 保存一个分片到磁盘（覆盖式写入，幂等，可重传同一片） */
    public void saveChunk(String uploadId, int chunkIndex, byte[] data) {
        File dir = chunkDir(uploadId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("创建分片目录失败: " + dir.getAbsolutePath());
        }
        File part = new File(dir, chunkIndex + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(data);
        } catch (IOException e) {
            throw new IllegalStateException("写入分片失败: " + e.getMessage(), e);
        }
    }

    /** 返回已成功接收的分片序号（升序），用于前端断点续传时跳过 */
    public List<Integer> receivedChunks(String uploadId) {
        File dir = chunkDir(uploadId);
        if (!dir.exists() || !dir.isDirectory()) {
            return Collections.emptyList();
        }
        List<Integer> list = new ArrayList<Integer>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".part"));
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                int dot = n.indexOf('.');
                try {
                    list.add(Integer.parseInt(n.substring(0, dot)));
                } catch (NumberFormatException ignored) {
                    // 跳过异常命名文件
                }
            }
        }
        Collections.sort(list);
        return list;
    }

    /** 是否已收齐全部分片 */
    public boolean isComplete(String uploadId, int totalChunks) {
        List<Integer> got = receivedChunks(uploadId);
        if (got.size() != totalChunks) {
            return false;
        }
        for (int i = 0; i < totalChunks; i++) {
            if (!got.contains(Integer.valueOf(i))) {
                return false;
            }
        }
        return true;
    }

    /** 合并所有分片为完整文件，返回本地路径；合并成功后会清理分片目录 */
    public String merge(String uploadId, int totalChunks, String fileName) {
        if (!isComplete(uploadId, totalChunks)) {
            throw new IllegalStateException("分片未全部到齐，无法合并（已收 "
                    + receivedChunks(uploadId).size() + "/" + totalChunks + "）");
        }
        File dir = chunkDir(uploadId);
        String safeName = (fileName == null || fileName.trim().isEmpty())
                ? "app.jar" : fileName.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        File merged = new File(dir, "final-" + safeName);
        try (OutputStream out = new FileOutputStream(merged)) {
            for (int i = 0; i < totalChunks; i++) {
                File part = new File(dir, i + ".part");
                if (!part.exists()) {
                    throw new IllegalStateException("缺少分片 " + i);
                }
                byte[] buf = new byte[8192];
                try (FileInputStream in = new FileInputStream(part)) {
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("合并分片失败: " + e.getMessage(), e);
        }
        // 合并完成后清理分片，仅保留 final 文件备查
        for (int i = 0; i < totalChunks; i++) {
            new File(dir, i + ".part").delete();
        }
        log.info("[分片] 合并完成 uploadId={} 产物={} 大小={}", uploadId, merged.getAbsolutePath(), merged.length());
        return merged.getAbsolutePath();
    }

    /** 清理某个上传的临时目录（含分片与已合并产物） */
    public void cleanup(String uploadId) {
        File dir = chunkDir(uploadId);
        try {
            Files.walk(dir.toPath()).sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }
}
