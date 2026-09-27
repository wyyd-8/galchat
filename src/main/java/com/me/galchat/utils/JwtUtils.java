package com.me.galchat.utils;

import com.me.galchat.constant.JwtConstant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtils {
    private final SecretKey secretKey;

    public JwtUtils(@Value("${galchat.jwt.signing-key:${GALCHAT_JWT_SIGNING_KEY:}}") String encodedSigningKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedSigningKey == null ? "" : encodedSigningKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT 签名密钥必须是 Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("JWT 签名密钥必须解码为 32 字节");
        }
        this.secretKey = Keys.hmacShaKeyFor(decoded);
    }

    /**
     * 生成JWT令牌
     * @param claims 自定义声明信息（如id、username等）
     * @return JWT令牌字符串
     */
    public String generateToken(Map<String, Object> claims) {
        return Jwts.builder()
                .signWith(secretKey, Jwts.SIG.HS256) // 使用新API指定算法
                .claims(claims)                         // 添加声明（新API）
                .expiration(new Date(System.currentTimeMillis() + JwtConstant.EXPIRATION_TIME))
                .compact();
    }

    /**
     * 解析JWT令牌
     * @param token JWT令牌字符串
     * @return 包含声明信息的Claims对象
     * @throws io.jsonwebtoken.JwtException 如果令牌无效/过期/被篡改
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)    // 设置验证密钥
                .build()
                .parseSignedClaims(token)  // 解析签名令牌
                .getPayload();             // 获取声明信息
    }
}
