package com.example.deploy.model;

/**
 * 发布 / 启停任务中某一步的实时状态，通过 SSE 推送给前端渲染进度条。
 */
public class StepStatus {

    /** 步骤序号，前端用它定位对应的 DOM 节点 */
    public int index;

    /** 步骤名称，如 “上传 jar 包” */
    public String name;

    /**
     * 状态：PENDING(等待) / RUNNING(执行中) / SUCCESS(成功) / FAIL(失败)
     */
    public String status = "PENDING";

    /** 该步骤的补充说明，如命令输出、错误信息 */
    public String detail = "";

    /** 进度百分比 0~100，仅上传步骤有意义 */
    public int progress = 0;

    /** 上传速度（字节/秒），仅上传步骤有意义，用于前端展示实时速度 */
    public long speed = 0;

    /** 耗时（毫秒） */
    public long costMs = 0;

    public StepStatus() {
    }

    public StepStatus(int index, String name) {
        this.index = index;
        this.name = name;
    }
}
