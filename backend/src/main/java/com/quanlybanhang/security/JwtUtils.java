package com.quanlybanhang.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Utility class xử lý JWT: tạo token, validate, extract thông tin.
 */
@Component
public class JwtUtils {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${app.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
                java.util.Base64.getEncoder().encodeToString(jwtSecret.getBytes()));
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Tạo Access Token từ authentication.
     */
    public String generateAccessToken(Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        return generateToken(userDetails.getUsername(), userDetails.getTokenVersion(), "access", jwtExpirationMs);
    }

    /**
     * Tạo Refresh Token từ authentication.
     */
    public String generateRefreshToken(Authentication authentication) {
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        return generateToken(userDetails.getUsername(), userDetails.getTokenVersion(), "refresh", refreshExpirationMs);
    }

    /**
     * Tạo Access Token từ username (dùng cho refresh).
     */
    public String generateAccessToken(String username, long tokenVersion) {
        return generateToken(username, tokenVersion, "access", jwtExpirationMs);
    }

    /** Access tokens in different browser tabs share the refresh token's revocation family. */
    public String generateAccessTokenForRefresh(String refreshToken) {
        Claims claims = getClaims(refreshToken);
        return generateToken(claims.getSubject(), getTokenVersion(refreshToken), "access",
                jwtExpirationMs, claims.get("sid", String.class));
    }

    public String getSessionId(String token) {
        return getClaims(token).get("sid", String.class);
    }

    private String generateToken(String username, long tokenVersion, String type, long expirationMs) {
        return generateToken(username, tokenVersion, type, expirationMs,
                "refresh".equals(type) ? java.util.UUID.randomUUID().toString() : null);
    }

    private String generateToken(String username, long tokenVersion, String type, long expirationMs, String sessionId) {
        return Jwts.builder()
                .subject(username)
                .claim("ver", tokenVersion)
                .claim("typ", type)
                .claim("sid", sessionId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    public long getTokenVersion(String token) {
        Number version = getClaims(token).get("ver", Number.class);
        return version == null ? -1L : version.longValue();
    }

    public String getTokenType(String token) {
        return getClaims(token).get("typ", String.class);
    }

    private Claims getClaims(String token) {
        return Jwts.parser().verifyWith(getSigningKey()).build()
                .parseSignedClaims(token).getPayload();
    }

    /**
     * Lấy username từ JWT token.
     */
    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Lấy thời điểm hết hạn từ JWT token.
     */
    public LocalDateTime getExpirationFromToken(String token) {
        Date expiration = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration();
        return expiration.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    /**
     * Lấy thời gian hết hạn của access token (ms).
     */
    public long getAccessTokenExpirationMs() {
        return jwtExpirationMs;
    }

    /**
     * Validate JWT token.
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (MalformedJwtException e) {
            logger.error("JWT token không hợp lệ: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            logger.error("JWT token đã hết hạn: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.error("JWT token không được hỗ trợ: {}", e.getMessage());
        } catch (io.jsonwebtoken.security.SecurityException e) {
            logger.debug("JWT signature rejected");
        } catch (IllegalArgumentException e) {
            logger.error("JWT claims string rỗng: {}", e.getMessage());
        }
        return false;
    }
}
