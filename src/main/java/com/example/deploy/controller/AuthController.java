package com.example.deploy.controller;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.deploy.model.R;
import com.example.deploy.service.AuthService;

/**
 * 登录验证接口：登录 / 状态查询 / 开关 / 登出。
 * 账号密码写死在配置（默认 admin / Admin123），开关可在主控制台实时切换并落盘。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpSession session) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        if (!authService.authenticate(username, password)) {
            return R.fail("账号或密码错误");
        }
        session.setAttribute("AUTH", Boolean.TRUE);
        Map<String, Object> res = new HashMap<String, Object>();
        res.put("username", authService.getUsername());
        return R.ok(res);
    }

    @GetMapping("/status")
    public R<Map<String, Object>> status(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        boolean loggedIn = session != null && Boolean.TRUE.equals(session.getAttribute("AUTH"));
        return R.ok(authService.status(loggedIn));
    }

    @PostMapping("/toggle")
    public R<Map<String, Object>> toggle(@RequestBody Map<String, Boolean> body) {
        Boolean enabled = body == null ? null : body.get("enabled");
        if (enabled == null) {
            return R.fail("缺少 enabled 参数");
        }
        // 关闭不需要登录；开启时必须已登录（由拦截器保证）
        authService.setEnabled(enabled);
        Map<String, Object> res = new HashMap<String, Object>();
        res.put("enabled", authService.isEnabled());
        return R.ok(res);
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpSession session) {
        session.removeAttribute("AUTH");
        return R.ok();
    }
}
