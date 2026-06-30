/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * DataAgent Web 应用程序的 HS256 JWT 服务。
 *
 * <p>令牌包含以下声明：
 * <ul>
 *   <li>{@code sub} — {@code userId}（HarnessAgent 命名空间的稳定身份键）
 *   <li>{@code username} — 显示名称
 *   <li>{@code roles} — 角色字符串列表
 * </ul>
 *
 * <p>签名密钥从 {@code dataagent.jwt.secret} 读取。默认是仅用于开发的占位符——
 * <strong>必须在生产环境中覆盖</strong>。
 */
@Service
public class JwtService {

    private static final long TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1_000L; // 7 天

    private final SecretKey signingKey;

    public JwtService(
            @Value(
                            "${dataagent.jwt.secret:dataagent-default-dev-secret-change-in-production-32chars}")
                    String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // JJWT 需要 HS256 至少 32 字节
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 为给定用户生成签名的 JWT。
     */
    public String generate(String userId, String username, List<String> roles) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(new Date(now))
                .expiration(new Date(now + TOKEN_TTL_MS))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解析并验证 JWT，返回其声明。
     *
     * @throws JwtException 如果令牌无效或已过期
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
    }

    /**
     * 从令牌中提取 {@code userId}（subject）而不进行完整验证
     * （调用方必须调用 {@link #parse} 进行安全提取）。
     */
    public String extractUserId(Claims claims) {
        return claims.getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }
}
