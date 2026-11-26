package com.example.deploy.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 一次发布 / 启停 / 回滚的实时任务。
 *
 * <p>任务在后台线程中执行，每进入/完成一个步骤都会把 {@link StepStatus} 推送给所有订阅的
 * SSE 连接；任务结束时推送最终结果并关闭连接。前端拿到 taskId 后通过
 * {@code /api/deploy/tasks/{taskId}/stream} 订阅进度。</p>
 */
public class DeployTask {

    /** 任务唯一 ID */
    public String taskId;

    /** 关联服务器 ID */
    public String serverId;

    /** 关联服务器名称（仅展示用） */
    public String serverName;

    /** 动作类型：DEPLOY / START / STOP / RESTART / ROLLBACK */
    public String action;

    /**
     * 任务状态：RUNNING(执行中) / DONE(成功结束) / FAILED(失败结束)
     */
    public String status = "RUNNING";

    /** 最终提示信息 */
    public String message = "";

    /** 开始时间（毫秒） */
    public long startTime;

    /** 结束时间（毫秒） */
    public long endTime;

    /** 预声明的步骤列表，按顺序执行 */
    public List<StepStatus> steps = new ArrayList<StepStatus>();

    /** 最终执行结果（任务结束时填充） */
    public DeployResult result;

    /** 订阅本任务的 SSE 连接，不参与 JSON 序列化 */
    @JsonIgnore
    public final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<SseEmitter>();

    /** 声明步骤（在提交后台线程前同步完成，保证 SSE 连上时已有完整步骤列表） */
    public void declareSteps(String... names) {
        steps.clear();
        for (int i = 0; i < names.length; i++) {
            steps.add(new StepStatus(i, names[i]));
        }
    }

    public StepStatus step(int i) {
        return steps.get(i);
    }

    /** 推送某一步的最新状态给所有订阅者 */
    public synchronized void emitStep(StepStatus s) {
        SseEmitter.SseEventBuilder ev = SseEmitter.event().name("step").data(s);
        for (SseEmitter e : emitters) {
            try {
                e.send(ev);
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }

    /** 任务成功/失败结束：推送最终结果并关闭所有连接 */
    public synchronized void complete(DeployResult r) {
        this.result = r;
        this.status = r.isSuccess() ? "DONE" : "FAILED";
        this.message = r.getMessage();
        this.endTime = System.currentTimeMillis();
        SseEmitter.SseEventBuilder ev = SseEmitter.event().name("done").data(r);
        for (SseEmitter e : emitters) {
            try {
                e.send(ev);
                e.complete();
            } catch (Exception ex) {
                // 连接已断开，忽略
            }
        }
        emitters.clear();
    }

  /** 重连场景：把最终结果补发给新连接的订阅者 */
  public synchronized void sendFinal(SseEmitter e) throws Exception {
    if (result != null) {
      e.send(SseEmitter.event().name("done").data(result));
    } else {
      // 没有结果对象（极少出现）：用 conn-error 事件告知前端连接级错误，避免使用浏览器保留的 error 事件名
      e.send(SseEmitter.event().name("conn-error").data(message));
    }
    e.complete();
  }

    @JsonIgnore
    public boolean isFinished() {
        return !"RUNNING".equals(status);
    }
}
