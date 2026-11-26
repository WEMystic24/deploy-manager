package com.example.deploy.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次远程目录列举的结果：解析后的绝对路径 + 条目列表。
 */
public class SftpDir {

    /** 解析后的绝对路径（例如 /opt/app） */
    public String path;

    /** 目录与文件条目，目录在前、文件在后，各自按名称排序 */
    public List<SftpEntry> entries = new ArrayList<SftpEntry>();
}
