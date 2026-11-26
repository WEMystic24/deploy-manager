package com.example.deploy.model;

/**
 * 远程命令执行结果。
 */
public class CommandResult {

    private String command;
    /** 命令执行时所在的远程工作目录（终端会话维护，cd 切换后更新） */
    private String cwd;
    /** 命令实际执行的远程账号（su 提权时为目标用户，如 root；否则为登录账号） */
    private String user;
    private int exitCode;
    private String stdout = "";
    private String stderr = "";
    private long costMs;

    public CommandResult() {
    }

    public CommandResult(String command) {
        this.command = command;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    /** stdout + stderr 合并输出，方便直接展示 */
    public String getOutput() {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && stdout.trim().length() > 0) {
            sb.append(stdout.trim());
        }
        if (stderr != null && stderr.trim().length() > 0) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(stderr.trim());
        }
        return sb.toString();
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
    }
}
