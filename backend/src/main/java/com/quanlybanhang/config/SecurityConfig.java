package com.quanlybanhang.config;

import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.DealerApiScopeFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Cấu hình Spring Security: JWT stateless, CORS, phân quyền 7 vai trò.
 *
 * Nguyên tắc: mặc định TỪ CHỐI — chỉ endpoint được khai báo mới truy cập được.
 * Chi tiết quyền trên từng API dùng @PreAuthorize ở controller.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final AuthEntryPointJwt unauthorizedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Tắt CSRF vì dùng JWT (stateless)
            .csrf(AbstractHttpConfigurer::disable)

            // Bật CORS (cấu hình ở CorsConfig)
            .cors(cors -> {})

            // Xử lý lỗi xác thực
            .exceptionHandling(exception ->
                exception.authenticationEntryPoint(unauthorizedHandler)
                        .accessDeniedHandler(accessDeniedHandler))

            // Không tạo session - JWT stateless
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Phân quyền các endpoint
            .authorizeHttpRequests(auth -> auth
                // ── Public: ai cũng truy cập được ──
                .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                        "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                .requestMatchers("/api/auth/**").authenticated()
                .requestMatchers("/api/users/**").authenticated()
                .requestMatchers("/api/test/public").permitAll()

                // Ma trận quyền tại controller quyết định quyền nghiệp vụ.
                // Chỉ cấu hình ma trận và nhật ký hệ thống dành riêng cho ADMIN.
                .requestMatchers("/api/admin/permissions", "/api/admin/permissions/**",
                        "/api/admin/audit-logs", "/api/admin/audit-logs/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**", "/api/manager/**",
                        "/api/warehouse/**", "/api/warehouses", "/api/warehouses/**",
                        "/api/warehouse-locations", "/api/warehouse-locations/**",
                        "/api/orders/**", "/api/products/**", "/api/categories/**",
                        "/api/suppliers/**", "/api/price-lists/**", "/api/volume-discounts/**",
                        "/api/dealers/**", "/api/portal/**", "/api/finance/**",
                        "/api/invoices/**", "/api/dealer-returns", "/api/dealer-returns/**",
                        "/api/returns", "/api/returns/**", "/api/reports/**",
                        "/api/sales-targets", "/api/sales-targets/**").authenticated()

                .requestMatchers("/api/test/**").authenticated()

                // Cho phép /error để hiển thị đúng mã lỗi
                .requestMatchers("/error").permitAll()

                // Mọi chức năng khác đều kiểm quyền ở tầng server, mặc định là từ chối.
                .anyRequest().denyAll()
            )

            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new DealerApiScopeFilter(accessDeniedHandler), JwtAuthenticationFilter.class);

        return http.build();
    }
}
