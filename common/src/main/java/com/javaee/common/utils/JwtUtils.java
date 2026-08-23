package com.javaee.common.utils;

import com.javaee.common.constant.CommonConstant;
import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.exception.TokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author wy
 * @description: JWT工具（生成/解析/验证）
 */
public class JwtUtils {

    private final SecretKey key;
    private final SecretKey previousKey;
    private final String issuer;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    public JwtUtils(String secret, String issuer, long accessTokenExpiration, long refreshTokenExpiration) {
        this(secret, null, issuer, accessTokenExpiration, refreshTokenExpiration);
    }

    public JwtUtils(String secret, String previousSecret, String issuer,
                    long accessTokenExpiration, long refreshTokenExpiration) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        if (accessTokenExpiration <= 0 || refreshTokenExpiration <= 0) {
            throw new IllegalStateException("JWT token expiration must be positive");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.previousKey = buildOptionalKey(previousSecret);
        this.issuer = issuer == null || issuer.isBlank() ? "doc-ai" : issuer.trim();
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    /**
     * 生成访问令牌
     * @param userId 用户ID
     * @param username 用户名
     * @return 令牌
     */
    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CommonConstant.TOKEN_CLAIM_USER_ID, userId);
        claims.put(CommonConstant.TOKEN_CLAIM_USERNAME, username);
        claims.put(CommonConstant.TOKEN_CLAIM_TYPE, CommonConstant.TOKEN_TYPE_ACCESS);
        return generateToken(claims, accessTokenExpiration);
    }

    /**
     * 生成访问令牌
     * @param userId 用户ID
     * @param username 用户名
     * @param role 角色
     * @return 令牌
     */
    public String generateToken(Long userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CommonConstant.TOKEN_CLAIM_USER_ID, userId);
        claims.put(CommonConstant.TOKEN_CLAIM_USERNAME, username);
        claims.put(CommonConstant.TOKEN_CLAIM_ROLE, role);
        claims.put(CommonConstant.TOKEN_CLAIM_TYPE, CommonConstant.TOKEN_TYPE_ACCESS);
        return generateToken(claims, accessTokenExpiration);
    }

    /**
     * 生成刷新令牌
     * @param userId 用户ID
     * @return 刷新令牌
     */
    public String generateRefreshToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CommonConstant.TOKEN_CLAIM_USER_ID, userId);
        claims.put(CommonConstant.TOKEN_CLAIM_TYPE, CommonConstant.TOKEN_TYPE_REFRESH);
        return generateToken(claims, refreshTokenExpiration);
    }

    /**
     * 生成令牌
     * @param claims 声明
     * @param expiration 过期时间
     * @return 令牌
     */
    private String generateToken(Map<String, Object> claims, long expiration) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .setClaims(claims)
                .setIssuer(issuer)
                .setSubject(String.valueOf(claims.get(CommonConstant.TOKEN_CLAIM_USER_ID)))
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /**
     * 解析令牌
     * @param token 令牌
     * @return 声明
     */
    public Claims parseToken(String token) {
        try {
            return parseWithKey(token, key);
        } catch (Exception e) {
            if (previousKey != null) {
                try {
                    return parseWithKey(token, previousKey);
                } catch (Exception ignored) {
                    // Fall through to the generic token error below.
                }
            }
            throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
        }
    }

    /**
     * 验证令牌
     * @param token 令牌
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        return validateAccessToken(token);
    }

    public boolean validateAccessToken(String token) {
        try {
            return hasTokenType(parseToken(token), CommonConstant.TOKEN_TYPE_ACCESS);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean validateRefreshToken(String token) {
        try {
            return hasTokenType(parseToken(token), CommonConstant.TOKEN_TYPE_REFRESH);
        } catch (Exception e) {
            return false;
        }
    }

    public Claims parseAccessToken(String token) {
        Claims claims = parseToken(token);
        requireTokenType(claims, CommonConstant.TOKEN_TYPE_ACCESS);
        return claims;
    }

    public Claims parseRefreshToken(String token) {
        Claims claims = parseToken(token);
        requireTokenType(claims, CommonConstant.TOKEN_TYPE_REFRESH);
        return claims;
    }

    /**
     * 获取用户ID
     * @param token 令牌
     * @return 用户ID
     */
    public Long getUserId(String token) {
        Claims claims = parseAccessToken(token);
        return claims.get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class);
    }

    public Long getRefreshTokenUserId(String token) {
        return parseRefreshToken(token).get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class);
    }

    /**
     * 获取用户名
     * @param token 令牌
     * @return 用户名
     */
    public String getUsername(String token) {
        Claims claims = parseAccessToken(token);
        return claims.get(CommonConstant.TOKEN_CLAIM_USERNAME, String.class);
    }

    /**
     * 获取角色
     * @param token 令牌
     * @return 角色
     */
    public String getRole(String token) {
        Claims claims = parseAccessToken(token);
        return claims.get(CommonConstant.TOKEN_CLAIM_ROLE, String.class);
    }

    /**
     * 检查令牌是否过期
     * @param token 令牌
     * @return 是否过期
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            Date expiration = claims.getExpiration();
            return expiration.before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 从请求头中提取令牌
     * @param authHeader 认证头
     * @return 令牌
     */
    public String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith(CommonConstant.TOKEN_PREFIX)) {
            return authHeader.substring(CommonConstant.TOKEN_PREFIX.length());
        }
        throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
    }

    private boolean hasTokenType(Claims claims, String expectedType) {
        return expectedType.equals(claims.get(CommonConstant.TOKEN_CLAIM_TYPE, String.class));
    }

    private void requireTokenType(Claims claims, String expectedType) {
        if (!hasTokenType(claims, expectedType)) {
            throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
        }
    }

    private Claims parseWithKey(String token, SecretKey signingKey) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private SecretKey buildOptionalKey(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_PREVIOUS_SECRET must contain at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(secretBytes);
    }
}
