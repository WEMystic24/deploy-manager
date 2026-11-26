package com.example.deploy.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.deploy.model.DynamicCodeRequiredException;
import com.example.deploy.model.R;

/**
 * 统一异常处理，保证前端始终拿到 {code,message} 结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 需要登录动态码：返回 code=428（需要额外信息），data 里带 serverId / serverName，
     * 前端据此弹窗要求输入动态码，提交后自动重试原请求。
     */
    @ExceptionHandler(DynamicCodeRequiredException.class)
    public R<Map<String, Object>> handleDynamicCodeRequired(DynamicCodeRequiredException e) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("dynamicCodeRequired", Boolean.TRUE);
        data.put("serverId", e.getServerId());
        data.put("serverName", e.getServerName());
        R<Map<String, Object>> r = new R<Map<String, Object>>();
        r.setCode(428);
        r.setMessage(e.getMessage());
        r.setData(data);
        return r;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleBadRequest(IllegalArgumentException e) {
        return R.fail(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("接口异常: {}", e.getMessage(), e);
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return R.fail(msg);
    }
}
