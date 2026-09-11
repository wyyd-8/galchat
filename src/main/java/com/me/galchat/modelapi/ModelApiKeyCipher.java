package com.me.galchat.modelapi;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public class ModelApiKeyCipher implements ApiKeyCipher {

    private static final String VERSION = "v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom;

    public ModelApiKeyCipher(String encodedMasterKey) {
        this(encodedMasterKey, new SecureRandom());
    }

    ModelApiKeyCipher(String encodedMasterKey, SecureRandom secureRandom) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedMasterKey == null
                    ? "" : encodedMasterKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("模型 API 主密钥必须是 Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("模型 API 主密钥必须解码为 32 字节");
        }
        this.key = new SecretKeySpec(decoded, "AES");
        this.secureRandom = secureRandom;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return VERSION + ":" + encoder.encodeToString(nonce)
                    + ":" + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("API Key 加密失败", exception);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            throw new IllegalStateException("加密的 API Key 不能为空");
        }
        String[] parts = ciphertext.split(":", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("不支持的 API Key 密文格式");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] nonce = decoder.decode(parts[1]);
            byte[] encrypted = decoder.decode(parts[2]);
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalStateException("API Key 密文 nonce 无效");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new IllegalStateException("API Key 密文校验失败", exception);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("API Key 解密失败", exception);
        }
    }
}
