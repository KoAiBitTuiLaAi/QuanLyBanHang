package com.quanlybanhang.security;

import com.quanlybanhang.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter chạy trước mỗi request để kiểm tra JWT token trong header Authorization.
 * Nếu token hợp lệ VÀ không nằm trong blacklist, set authentication vào SecurityContext.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtils jwtUtils;
    private final UserDetailsServiceImpl userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = parseJwt(request);
            if (jwt != null && jwtUtils.validateToken(jwt)
                    && "access".equals(jwtUtils.getTokenType(jwt))) {
                // Kiểm tra token có bị blacklist (đăng xuất, đổi mật khẩu, khoá tài khoản)
                if (tokenBlacklistService.isBlacklisted(jwt) || tokenBlacklistService.isSessionRevoked(jwt)) {
                    logger.debug("Token bị blacklist, bỏ qua xác thực");
                } else {
                    String username = jwtUtils.getUsernameFromToken(jwt);

                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    if (userDetails instanceof UserDetailsImpl details
                            && details.isEnabled()
                            && details.getTokenVersion() == jwtUtils.getTokenVersion(jwt)) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(
                                new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Không thể xác thực người dùng: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Lấy JWT token từ header Authorization: Bearer <token>
     */
    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        return null;
    }
}
