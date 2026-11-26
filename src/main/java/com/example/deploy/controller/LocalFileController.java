package com.example.deploy.controller;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.deploy.model.R;

/**
 * 本机文件浏览接口：给「本机路径发布」等处的路径选择弹窗提供目录列表。
 * 应用为单机免密部署，直接浏览本机文件系统。
 */
@RestController
@RequestMapping("/api/local-files")
public class LocalFileController {

    /** 路径选择弹窗的默认目录（application.yml: deploy.local-file-default-dir），未配置或不存在时用用户主目录 */
    private final String defaultDir;

    public LocalFileController(
            @org.springframework.beans.factory.annotation.Value("${deploy.local-file-default-dir:}") String defaultDir) {
        this.defaultDir = defaultDir;
    }

    /** 列出某目录内容；path 为空时从默认目录开始 */
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(value = "path", required = false) String path) {
        try {
            File home = defaultDir != null && !defaultDir.trim().isEmpty()
                    ? new File(defaultDir.trim()) : null;
            if (home == null || !home.isDirectory()) {
                home = new File(System.getProperty("user.home"));
            }
            File dir;
            if (path == null || path.trim().isEmpty()) {
                dir = home;
            } else {
                dir = new File(path.trim());
            }
            if (!dir.exists()) {
                // 配置的路径不存在：回退到默认目录
                dir = home;
            }
            File canonical = dir.getCanonicalFile();
            if (!canonical.isDirectory()) {
                // 传的是文件：定位到其所在目录
                canonical = canonical.getParentFile();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", canonical.getAbsolutePath());
            result.put("parent", canonical.getParent());
            result.put("home", new File(System.getProperty("user.home")).getCanonicalPath());

            // 盘符 / 根目录列表
            List<String> roots = new ArrayList<>();
            File[] rootFiles = File.listRoots();
            if (rootFiles != null) {
                for (File r : rootFiles) {
                    roots.add(r.getAbsolutePath());
                }
            }
            result.put("roots", roots);

            // 快速访问：主目录 / 桌面 / 下载 / 文档
            List<Map<String, String>> quick = new ArrayList<>();
            addQuick(quick, "主目录", System.getProperty("user.home"));
            addQuick(quick, "桌面", System.getProperty("user.home") + File.separator + "Desktop");
            addQuick(quick, "下载", System.getProperty("user.home") + File.separator + "Downloads");
            addQuick(quick, "文档", System.getProperty("user.home") + File.separator + "Documents");
            result.put("quick", quick);

            // 目录内容：文件夹在前、按名称排序
            List<Map<String, Object>> entries = new ArrayList<>();
            File[] children = canonical.listFiles();
            if (children != null) {
                File[] sorted = children.clone();
                Arrays.sort(sorted, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        if (a.isDirectory() != b.isDirectory()) {
                            return a.isDirectory() ? -1 : 1;
                        }
                        return a.getName().toLowerCase(Locale.ROOT)
                                .compareTo(b.getName().toLowerCase(Locale.ROOT));
                    }
                });
                for (File f : sorted) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("name", f.getName());
                    e.put("dir", f.isDirectory());
                    e.put("size", f.isDirectory() ? null : f.length());
                    e.put("lastModified", f.lastModified());
                    entries.add(e);
                }
            }
            result.put("entries", entries);
            return R.ok(result);
        } catch (IOException e) {
            return R.fail("读取目录失败：" + e.getMessage());
        }
    }

    private void addQuick(List<Map<String, String>> quick, String label, String path) {
        File f = new File(path);
        if (f.isDirectory()) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("label", label);
            q.put("path", path);
            quick.add(q);
        }
    }
}
