package com.example.deploy.config;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.example.deploy.service.AuthService;

/**
 * 登录拦截器：当「需要登录」开关开启时，未登录访问受保护资源将被拦截。
 * 放行项：登录页 /login.html、登录接口 /api/auth/login、状态接口 /api/auth/status、
 * 开关接口 /api/auth/toggle（开启状态下须已登录，关闭状态下任何人可开启）、静态资源（css/js/图片等）。
 * 未登录访问页面类资源 → 302 跳转到 /login.html；访问 API → 返回 401 JSON。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    /** 免密开关：true=完全关闭登录校验，任何页面/接口直接放行（登录入口已从前端移除） */
    private static final boolean AUTH_DISABLED = true;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 免密模式：直接放行，忽略 authService.isEnabled() 与 auth.json 的残留状态。
        // 如需恢复登录校验，把 AUTH_DISABLED 改为 false 并在前端恢复登录入口即可。
        if (AUTH_DISABLED) {
            return true;
        }
        if (!authService.isEnabled()) {
            return true;
        }
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();

        // 登录页与登录接口始终放行
        if (uri.equals(ctx + "/login.html")) {
            return true;
        }
        if (uri.startsWith(ctx + "/api/auth/login") || uri.equals(ctx + "/api/auth/status")) {
            return true;
        }
        // 开关接口：开启状态下必须已登录，关闭状态下任何人可开启
        if (uri.equals(ctx + "/api/auth/toggle")) {
            if (isAuthed(request)) {
                return true;
            }
            writeUnauthorized(response);
            return false;
        }
        // 静态资源放行（保证登录页样式/脚本可用）
        if (isAsset(uri)) {
            return true;
        }
        // 已登录放行
        if (isAuthed(request)) {
            return true;
        }
        // 未登录
        if (uri.startsWith(ctx + "/api/")) {
            writeUnauthorized(response);
        } else {
            // 带上原始访问地址（含查询串，如 board.html?serverId=xxx），登录后可回跳到原页面
            String next = uri + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
            response.sendRedirect(ctx + "/login.html?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8.name()));
        }
        return false;
    }

    private boolean isAuthed(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute("AUTH"));
    }

    private boolean isAsset(String uri) {
        return uri.endsWith(".css") || uri.endsWith(".js") || uri.endsWith(".ico")
                || uri.endsWith(".png") || uri.endsWith(".svg") || uri.endsWith(".woff")
                || uri.endsWith(".woff2") || uri.endsWith(".ttf") || uri.endsWith(".map");
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"code\":401,\"message\":\"未登录\"}");
    }
}
