package com.me.galchat.utils;

import com.me.galchat.constant.JwtConstant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

public class JwtUtils {
    // 密钥字符串转换得到的SecretKey
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(JwtConstant.SECRET_STRING.getBytes(StandardCharsets.UTF_8));

    /**
     * 生成JWT令牌
     * @param claims 自定义声明信息（如id、username等）
     * @return JWT令牌字符串
     */
    public static String generateToken(Map<String, Object> claims) {
        return Jwts.builder()
                .signWith(SECRET_KEY, Jwts.SIG.HS256) // 使用新API指定算法
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
    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(SECRET_KEY)    // 设置验证密钥
                .build()
                .parseSignedClaims(token)  // 解析签名令牌
                .getPayload();             // 获取声明信息
    }
}
