package com.example.deploy.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.deploy.model.R;

/**
 * 统一异常处理，保证前端始终拿到 {code,message} 结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
