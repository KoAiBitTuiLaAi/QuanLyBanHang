package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.*;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.JwtResponse;
import com.quanlybanhang.security.UserDetailsImpl;
import com.quanlybanhang.service.AuthService;
import com.quanlybanhang.service.AvatarService;
import com.quanlybanhang.service.PasswordResetService;
import com.quanlybanhang.service.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Controller xử lý các API liên quan đến xác thực:
 * - POST /api/auth/login           → Đăng nhập
 * - POST /api/auth/refresh         → Refresh token
 * - POST /api/auth/logout          → Đăng xuất (S1-02)
 * - POST /api/auth/change-password → Đổi mật khẩu (S1-04)
 * - POST /api/auth/forgot-password → Quên mật khẩu
 * - POST /api/auth/reset-password  → Đặt lại mật khẩu
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final AdminUserService adminUserService;
    private final AvatarService avatarService;

    private static final String SESSION_COOKIE = "qlbh_refresh";
    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;
    @Value("${app.auth.cookie-secure:false}")
    private boolean secureCookie;

    /**
     * Đăng nhập.
     */
    @PostMapping("/login")
    public ResponseEntity<JwtResponse> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        boolean browser = browserRequest(httpRequest);
        JwtResponse response = authService.login(request);
        if (browser) {
            String previous = sessionCookie(httpRequest);
            if (StringUtils.hasText(previous)) authService.logout(null, previous);
            writeSessionCookie(httpRequest, httpResponse, response);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    /**
     * Refresh access token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<JwtResponse> refreshToken(@RequestBody(required = false) java.util.Map<String, String> request,
                                                    HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        boolean browser = browserRequest(httpRequest);
        String refreshToken = request == null ? null : request.get("refreshToken");
        // Browser cookie is authoritative; a stale tab must not replace a newer login.
        String cookie = browser ? sessionCookie(httpRequest) : null;
        if (StringUtils.hasText(cookie)) refreshToken = cookie;
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadCredentialsException("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
        }
        try {
            JwtResponse response = authService.refreshToken(refreshToken);
            if (browser) writeSessionCookie(httpRequest, httpResponse, response);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
        } catch (BadCredentialsException ex) {
            if (browser) clearSessionCookie(httpRequest, httpResponse);
            throw ex;
        }
    }

    /**
     * Đăng xuất — vô hiệu hoá token phía server (S1-02).
     * Nhận access token qua header (nếu còn) và refresh token qua body.
     * Endpoint vẫn xử lý refresh token khi access token đã hết hạn.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse> logout(HttpServletRequest request,
                                               @RequestBody(required = false) java.util.Map<String, String> body,
                                               HttpServletResponse httpResponse) {
        boolean browser = browserRequest(request);
        String token = parseJwt(request);
        String refreshToken = body == null ? null : body.get("refreshToken");
        String cookie = browser ? sessionCookie(request) : null;
        if (StringUtils.hasText(cookie)) refreshToken = cookie;
        authService.logout(token, refreshToken);
        if (browser) clearSessionCookie(request, httpResponse);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success("Đăng xuất thành công"));
    }

    /** Cookie operations require an explicit, CORS-preflighted request from a trusted frontend. */
    private boolean browserRequest(HttpServletRequest request) {
        if (!"1".equals(request.getHeader("X-Browser-Session"))) return false;
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || java.util.Arrays.stream(allowedOrigins).noneMatch(origin::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nguồn yêu cầu phiên đăng nhập không được phép");
        }
        return true;
    }

    private String sessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder cookieBuilder(HttpServletRequest request, String value) {
        return ResponseCookie.from(SESSION_COOKIE, value).httpOnly(true)
                .secure(secureCookie || request.isSecure()).sameSite("Strict").path("/api/auth");
    }

    private void writeSessionCookie(HttpServletRequest request, HttpServletResponse response, JwtResponse data) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookieBuilder(request, data.getRefreshToken()).build().toString());
        data.setRefreshToken(null); // Browser JavaScript never receives the new refresh credential.
    }

    private void clearSessionCookie(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookieBuilder(request, "").maxAge(0).build().toString());
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse> me(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success("Thông tin người dùng",
                adminUserService.getByUsername(authentication.getName())));
    }

    @GetMapping("/permissions")
    public ResponseEntity<ApiResponse> permissions(Authentication authentication) {
        var authorities = authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority).sorted().toList();
        return ResponseEntity.ok(ApiResponse.success("Quyền hiện tại", java.util.Map.of(
                "roles", authorities.stream().filter(value -> value.startsWith("ROLE_")).toList(),
                "permissions", authorities.stream().filter(value -> !value.startsWith("ROLE_")).toList())));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> updateAvatar(
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        var user = avatarService.updateAvatar(authentication.getName(), file);
        Map<String, String> data = Map.of(
                "avatarUrl", AvatarService.avatarUrl(user, false),
                "avatarThumbnailUrl", AvatarService.avatarUrl(user, true));
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật ảnh đại diện", data));
    }

    @GetMapping("/users/{id}/avatar")
    public ResponseEntity<byte[]> avatar(@PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean thumbnail) {
        return avatarService.getAvatar(id, thumbnail)
                .map(content -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache())
                        .contentType(MediaType.parseMediaType(content.contentType()))
                        .body(content.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Đổi mật khẩu khi đang đăng nhập (S1-04).
     */
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        String currentToken = parseJwt(httpRequest);

        ApiResponse response = authService.changePassword(
                userDetails.getUsername(), request, currentToken);

        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Quên mật khẩu - gửi email reset.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.sendResetPasswordEmail(request.getEmail());
        return ResponseEntity.ok(
                ApiResponse.success("Email đặt lại mật khẩu đã được gửi. Vui lòng kiểm tra hộp thư."));
    }

    /**
     * Đặt lại mật khẩu bằng token từ email.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(
                ApiResponse.success("Mật khẩu đã được đặt lại thành công. Vui lòng đăng nhập lại."));
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
