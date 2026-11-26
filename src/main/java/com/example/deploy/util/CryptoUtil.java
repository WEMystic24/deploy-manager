package com.example.deploy.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 服务器密码的对称加解密，避免密码明文落盘。
 * 密文统一带 ENC: 前缀，因此可以兼容 yml 中直接写明文密码的场景。
 */
public final class CryptoUtil {

    private static final String PREFIX = "ENC:";
    private static final String ALGORITHM = "AES/ECB/PKCS5Padding";

    private CryptoUtil() {
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** 已加密则原样返回，明文则加密 */
    public static String encrypt(String plain, String secretKey) {
        if (plain == null || plain.length() == 0 || isEncrypted(plain)) {
            return plain;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keyOf(secretKey));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("密码加密失败: " + e.getMessage(), e);
        }
    }

    /** 非 ENC: 前缀视为明文，原样返回 */
    public static String decrypt(String value, String secretKey) {
        if (value == null || !isEncrypted(value)) {
            return value;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keyOf(secretKey));
            byte[] decoded = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("密码解密失败，请检查 deploy.secret-key 是否被修改: " + e.getMessage(), e);
        }
    }

    private static SecretKeySpec keyOf(String secretKey) throws Exception {
        String seed = secretKey == null || secretKey.length() == 0 ? "DeployManager" : secretKey;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(Arrays.copyOf(hash, 16), "AES");
    }
}
