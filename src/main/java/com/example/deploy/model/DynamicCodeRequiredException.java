package com.example.deploy.model;

/**
 * 服务器开启了登录动态码（MFA），但当前还没有有效动态码时抛出。
 *
 * <p>由 {@link com.example.deploy.controller.GlobalExceptionHandler} 转成 code=428 的响应，
 * 携带 serverId / serverName，前端据此弹窗要求用户输入动态码，提交后自动重试原请求。</p>
 */
public class DynamicCodeRequiredException extends RuntimeException {

    private final String serverId;
    private final String serverName;

    public DynamicCodeRequiredException(String serverId, String serverName) {
        super("服务器「" + (serverName == null || serverName.trim().isEmpty() ? serverId : serverName)
                + "」已开启动态码，请先输入动态码再连接");
        this.serverId = serverId;
        this.serverName = serverName;
    }

    public String getServerId() {
        return serverId;
    }

    public String getServerName() {
        return serverName;
    }
}
