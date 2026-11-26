package com.example.deploy.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 登录动态码（短信/OTP/谷歌验证码等 MFA）的临时存放。
 *
 * <p>动态码只用于「建立 SSH 连接」那一刻：连接时把它拼接到登录密码末尾，
 * 因此不需要、也不应该持久化到 servers.json。这里放内存，按服务器单独保存，
 * 超过 {@link #TTL_MS} 自动失效（下次连接前前端会重新弹窗要求输入）。</p>
 *
 * <p>已建立的连接由 {@link com.example.deploy.ssh.SshService} 的连接池保活复用，
 * 期间不会再次校验动态码；连接断开需要重连时才会重新取用（已过期则重新提示输入）。</p>
 */
@Component
public class DynamicCodeStore {

    private static final Logger log = LoggerFactory.getLogger(DynamicCodeStore.class);

    /**
     * 动态码保留时长：5 分钟，与连接池的空闲回收时间一致。
     * 只影响「重连时还能不能复用上次输入的动态码」，不影响已建立的连接。
     */
    private static final long TTL_MS = 5 * 60 * 1000L;

    /** 动态码格式：4~16 位字母或数字（多数 MFA 是 6 位数字，个别系统带字母） */
    private static final String CODE_PATTERN = "[A-Za-z0-9]{4,16}";

    private final Map<String, Entry> codes = new ConcurrentHashMap<String, Entry>();

    /** 保存某台服务器的动态码（覆盖旧值） */
    public void put(String serverId, String code) {
        if (serverId == null || serverId.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少服务器标识，无法提交动态码");
        }
        String c = code == null ? "" : code.replaceAll("\\s+", "");
        if (c.isEmpty()) {
            throw new IllegalArgumentException("请输入动态码");
        }
        if (!c.matches(CODE_PATTERN)) {
            throw new IllegalArgumentException("动态码格式不正确：应为 4~16 位字母或数字");
        }
        codes.put(serverId, new Entry(c, System.currentTimeMillis() + TTL_MS));
        log.debug("已保存服务器 {} 的动态码（{} 分钟内有效）", serverId, TTL_MS / 60000);
    }

    /** 取动态码；不存在或已过期返回 null */
    public String get(String serverId) {
        if (serverId == null) {
            return null;
        }
        Entry e = codes.get(serverId);
        if (e == null) {
            return null;
        }
        if (e.expireAt <= System.currentTimeMillis()) {
            codes.remove(serverId, e);
            return null;
        }
        return e.code;
    }

    public boolean hasValid(String serverId) {
        return get(serverId) != null;
    }

    /** 清空某台服务器的动态码（改密码、关闭动态码、删除服务器、认证失败时调用） */
    public void clear(String serverId) {
        if (serverId != null) {
            codes.remove(serverId);
        }
    }

    /** 动态码有效期（秒），供前端提示 */
    public long ttlSeconds() {
        return TTL_MS / 1000L;
    }

    private static final class Entry {
        final String code;
        final long expireAt;

        Entry(String code, long expireAt) {
            this.code = code;
            this.expireAt = expireAt;
        }
    }
}
