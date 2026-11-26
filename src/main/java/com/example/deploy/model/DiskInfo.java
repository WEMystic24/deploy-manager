package com.example.deploy.model;

/**
 * 一台磁盘（来自 {@code fdisk -l} / {@code lsblk}）的结构化信息。
 *
 * <p>仅用于「磁盘管理」扫描结果展示：device 为设备路径（如 /dev/sdb），
 * mountable 表示是否为可安全整盘格式化挂载的全新磁盘（未挂载、无文件系统、非系统盘）。</p>
 */
public class DiskInfo {

    /** 设备路径，如 /dev/sdb */
    private String device;

    /** 容量（字节） */
    private long sizeBytes;

    /** 容量的可读描述（由前端根据 sizeBytes 渲染，这里保留便于调试） */
    private String sizeHuman;

    /** 设备型号（可能为空） */
    private String model;

    /** 顶层文件系统类型（全新磁盘通常为空） */
    private String fstype;

    /** 已挂载点（已挂载磁盘才有） */
    private String mountpoint;

    /** 是否可安全整盘格式化挂载 */
    private boolean mountable;

    /** 不可挂载时的原因说明；可挂载时为「全新未格式化磁盘，可整盘格式化挂载」 */
    private String reason;

    /** 磁盘 UUID（整盘文件系统创建后才会有，扫描时通常为空） */
    private String uuid;

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSizeHuman() {
        return sizeHuman;
    }

    public void setSizeHuman(String sizeHuman) {
        this.sizeHuman = sizeHuman;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getFstype() {
        return fstype;
    }

    public void setFstype(String fstype) {
        this.fstype = fstype;
    }

    public String getMountpoint() {
        return mountpoint;
    }

    public void setMountpoint(String mountpoint) {
        this.mountpoint = mountpoint;
    }

    public boolean isMountable() {
        return mountable;
    }

    public void setMountable(boolean mountable) {
        this.mountable = mountable;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }
}
