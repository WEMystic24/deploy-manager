package com.example.deploy.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.deploy.config.DeployProperties;
import com.example.deploy.model.OperationLog;
import com.example.deploy.model.ServerConfig;

/**
 * 操作记录：内存保留最近 300 条，同时追加写入文件。
 */
@Service
public class OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);
    private static final int MAX_MEMORY = 300;

    private final DeployProperties properties;
    private final LinkedList<OperationLog> recent = new LinkedList<OperationLog>();

    public OperationLogService(DeployProperties properties) {
        this.properties = properties;
    }

    public void record(ServerConfig config, String action, boolean success, long costMs, String message) {
        record(config == null ? "" : config.getId(), config == null ? "" : config.getName(),
                action, success, costMs, message);
    }

    /** 按字符串记录（发布配置维度时 serverName 可拼上发布目标名） */
    public void record(String serverId, String serverName, String action, boolean success, long costMs, String message) {
        OperationLog item = new OperationLog(now(), serverId, serverName, action, success, costMs, message);
        synchronized (recent) {
            recent.addFirst(item);
            while (recent.size() > MAX_MEMORY) {
                recent.removeLast();
            }
        }
        appendFile(item);
        // 同时打印到控制台（IDEA 运行输出），方便实时查看每一次操作
        log.info("操作记录 {} | {} | {} | {}ms | {}",
                item.getServerName() == null ? "" : item.getServerName(),
                item.getAction(),
                item.isSuccess() ? "成功" : "失败",
                Long.valueOf(item.getCostMs()),
                item.getMessage() == null ? "" : item.getMessage());
    }

    public List<OperationLog> list(int limit) {
        synchronized (recent) {
            int size = Math.min(limit <= 0 ? 50 : limit, recent.size());
            return new ArrayList<OperationLog>(recent.subList(0, size));
        }
    }

    private void appendFile(OperationLog item) {
        Writer writer = null;
        try {
            File file = new File(properties.getOperationLogFile());
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
            writer.write(String.format("%s\t%s\t%s\t%s\t%dms\t%s%n",
                    item.getTime(),
                    item.getServerName() == null ? "" : item.getServerName(),
                    item.getAction(),
                    item.isSuccess() ? "成功" : "失败",
                    Long.valueOf(item.getCostMs()),
                    item.getMessage() == null ? "" : item.getMessage().replace("\n", " ")));
        } catch (Exception e) {
            log.warn("写操作日志失败: {}", e.getMessage());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
