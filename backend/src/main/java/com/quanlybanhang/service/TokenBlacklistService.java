package com.quanlybanhang.service;

import com.quanlybanhang.model.TokenBlacklist;
import com.quanlybanhang.repository.TokenBlacklistRepository;
import com.quanlybanhang.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Service quản lý token blacklist — thu hồi JWT tokens.
 *
 * Dùng khi:
 * - Đăng xuất (LOGOUT)
 * - Đổi mật khẩu (PASSWORD_CHANGE) — thu hồi tất cả phiên khác
 * - Khoá tài khoản (ACCOUNT_LOCK) — thu hồi ngay lập tức
 */
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final Logger logger = LoggerFactory.getLogger(TokenBlacklistService.class);

    private final TokenBlacklistRepository blacklistRepository;
    private final JwtUtils jwtUtils;

    /**
     * Thêm một token vào blacklist.
     */
    @Transactional
    public void blacklistToken(String token, String reason) {
        if (blacklistRepository.existsByToken(token)) {
            return; // Đã blacklist rồi
        }

        String username = jwtUtils.getUsernameFromToken(token);
        LocalDateTime expiresAt = jwtUtils.getExpirationFromToken(token);

        TokenBlacklist entry = TokenBlacklist.builder()
                .token(token)
                .username(username)
                .reason(reason)
                .expiresAt(expiresAt)
                .build();

        blacklistRepository.save(entry);
        logger.info("Token blacklisted: user='{}', reason='{}'", username, reason);
    }

    /**
     * Kiểm tra token có bị blacklist không.
     */
    public boolean isBlacklisted(String token) {
        return blacklistRepository.existsByToken(token);
    }

    public boolean isSessionRevoked(String token) {
        String sessionId = jwtUtils.getSessionId(token);
        return sessionId != null && blacklistRepository.existsByToken("SESSION:" + sessionId);
    }

    /** The refresh expiry keeps this marker alive for the whole browser session. */
    @Transactional
    public void blacklistSession(String refreshToken, String reason) {
        String sessionId = jwtUtils.getSessionId(refreshToken);
        if (sessionId == null || blacklistRepository.existsByToken("SESSION:" + sessionId)) return;
        blacklistRepository.save(TokenBlacklist.builder().token("SESSION:" + sessionId)
                .username(jwtUtils.getUsernameFromToken(refreshToken)).reason(reason)
                .expiresAt(jwtUtils.getExpirationFromToken(refreshToken)).build());
    }

    /**
     * Xoá các token đã hết hạn tự nhiên (cleanup — gọi từ scheduled job).
     */
    @Transactional
    public int cleanupExpiredTokens() {
        int deleted = blacklistRepository.deleteExpiredTokens(LocalDateTime.now());
        if (deleted > 0) {
            logger.info("Cleaned up {} expired blacklisted tokens", deleted);
        }
        return deleted;
    }
}
