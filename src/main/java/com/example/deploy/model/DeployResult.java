package com.example.deploy.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次发布 / 启停操作的结果，包含每一步的执行明细。
 */
public class DeployResult {

    private boolean success = true;
    private String message;
    private long costMs;
    private List<Step> steps = new ArrayList<Step>();

    public static class Step {
        private String name;
        private boolean success;
        private String detail;
        private long costMs;

        public Step() {
        }

        public Step(String name, boolean success, String detail, long costMs) {
            this.name = name;
            this.success = success;
            this.detail = detail;
            this.costMs = costMs;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }

        public long getCostMs() {
            return costMs;
        }

        public void setCostMs(long costMs) {
            this.costMs = costMs;
        }
    }

    public DeployResult step(String name, boolean ok, String detail, long costMs) {
        this.steps.add(new Step(name, ok, detail, costMs));
        if (!ok) {
            this.success = false;
        }
        return this;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
    }

    public List<Step> getSteps() {
        return steps;
    }

    public void setSteps(List<Step> steps) {
        this.steps = steps;
    }
}
