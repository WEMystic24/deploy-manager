package com.example.deploy.model;

import java.util.List;

/**
 * 「磁盘管理」挂载请求：选择一台服务器，对其上若干台全新磁盘执行整盘格式化挂载。
 *
 * <p>约定（来自需求确认）：整盘直接格式化挂载（不分区）、默认 ext4、写入 /etc/fstab 实现重启自动挂载。</p>
 */
public class DiskMountRequest {

    /** 目标服务器 id */
    private String serverId;

    /** 文件系统类型：ext4（默认）/ xfs */
    private String fsType = "ext4";

    /** 是否把挂载写入 /etc/fstab（默认 true，实现重启自动挂载） */
    private boolean addFstab = true;

    /** 待挂载的磁盘列表，每项含设备路径与挂载点 */
    private List<DiskItem> disks;

    public static class DiskItem {
        /** 设备路径，如 /dev/sdb */
        public String device;
        /** 挂载点，如 /data */
        public String mountPoint;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getFsType() {
        return fsType;
    }

    public void setFsType(String fsType) {
        this.fsType = fsType;
    }

    public boolean isAddFstab() {
        return addFstab;
    }

    public void setAddFstab(boolean addFstab) {
        this.addFstab = addFstab;
    }

    public List<DiskItem> getDisks() {
        return disks;
    }

    public void setDisks(List<DiskItem> disks) {
        this.disks = disks;
    }
}
