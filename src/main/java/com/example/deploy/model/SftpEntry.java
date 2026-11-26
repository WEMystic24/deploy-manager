package com.example.deploy.model;

/**
 * 远程目录/文件条目（SFTP 浏览用）。
 */
public class SftpEntry {

    /** 名称 */
    public String name;

    /** 是否为目录 */
    public boolean dir;

    /** 大小（字节，目录为 0） */
    public long size;

    /** 修改时间（YYYY-MM-DD HH:MM） */
    public String modTime;

    /** 权限串，如 drwxr-xr-x */
    public String perms;

    /** 属主 */
    public String owner;

    /** 属组 */
    public String group;
}
