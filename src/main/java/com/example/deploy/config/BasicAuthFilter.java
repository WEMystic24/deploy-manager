package com.example.deploy.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 可选的访问认证（deploy.auth.enabled=true 时生效）。
 * 由于本工具握有服务器账号密码，强烈建议只在内网使用，并开启认证。
 */
@Component
public class BasicAuthFilter extends OncePerRequestFilter {

    private final DeployProperties properties;

    public BasicAuthFilter(DeployProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        DeployProperties.Auth auth = properties.getAuth();
        if (auth == null || !auth.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int i = decoded.indexOf(':');
                String user = i < 0 ? decoded : decoded.substring(0, i);
                String pwd = i < 0 ? "" : decoded.substring(i + 1);
                if (auth.getUsername().equals(user) && auth.getPassword().equals(pwd)) {
                    chain.doFilter(request, response);
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // base64 解析失败，按未认证处理
            }
        }
        response.setHeader("WWW-Authenticate", "Basic realm=\"deploy-manager\"");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"需要登录\"}");
    }
}
