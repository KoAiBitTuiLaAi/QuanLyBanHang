package com.quanlybanhang.config;

import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.security.RestAccessDeniedHandler;
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

                // ── Admin only: quản lý user, cấu hình hệ thống, ma trận phân quyền ──
                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                // ── Quản lý: ADMIN + SALES_MANAGER + WAREHOUSE_MANAGER ──
                .requestMatchers("/api/manager/**").hasAnyRole(
                        "ADMIN", "SALES_MANAGER", "WAREHOUSE_MANAGER")

                // ── Điều chỉnh kho: CHỈ ADMIN + WAREHOUSE_MANAGER (đảm bảo SALES và WAREHOUSE_STAFF không sửa được tồn kho!) ──
                .requestMatchers("/api/warehouse/adjust", "/api/warehouse/adjust/**").hasAnyRole(
                        "ADMIN", "WAREHOUSE_MANAGER")

                // Dữ liệu tồn và chứng từ được phép xem bởi quản lý kinh doanh/người chỉ xem.
                .requestMatchers("/api/warehouse/options", "/api/warehouse/inventory", "/api/warehouse/vouchers", "/api/warehouse/vouchers/**")
                        .hasAnyRole("ADMIN", "WAREHOUSE_MANAGER", "WAREHOUSE_STAFF", "SALES_MANAGER", "VIEWER")

                // ── Kho thông thường (nhập/xuất/xem): ADMIN + WAREHOUSE_MANAGER + WAREHOUSE_STAFF ──
                .requestMatchers("/api/warehouse/**").hasAnyRole(
                        "ADMIN", "WAREHOUSE_MANAGER", "WAREHOUSE_STAFF")

                // ── Đơn hàng: mọi role trừ VIEWER có thể tạo/sửa, nhưng VIEWER có thể xem ──
                // Chi tiết dùng @PreAuthorize trên từng method
                .requestMatchers("/api/orders/**").authenticated()

                // ── Sản phẩm & Nhóm hàng: mọi role đã đăng nhập có thể xem (thao tác quản lý dùng @PreAuthorize) ──
                .requestMatchers("/api/products/**").authenticated()
                .requestMatchers("/api/categories/**").authenticated()
                .requestMatchers("/api/suppliers/**").authenticated()

                // ── Bảng giá (S2-10): mọi role đã đăng nhập có thể xem (thao tác quản lý dùng @PreAuthorize) ──
                .requestMatchers("/api/price-lists/**").authenticated()

                // ── Chính sách chiết khấu theo sản lượng (S3-01): mọi role đã đăng nhập có thể xem (thao tác quản lý dùng @PreAuthorize) ──
                .requestMatchers("/api/volume-discounts/**").authenticated()
                // ── Quản lý hồ sơ đại lý (S3-03): mọi role hợp lệ đã đăng nhập; chi tiết phân quyền qua @PreAuthorize ──
                .requestMatchers("/api/dealers/**").authenticated()

                // ── Cổng đại lý tự đặt hàng và theo dõi đơn (S4-10, S5-01) ──
                .requestMatchers("/api/portal/**").authenticated()

                // ── Khách hàng: ADMIN, SALES_MANAGER, SALES, VIEWER ──
                .requestMatchers("/api/customers/**").hasAnyRole(
                        "ADMIN", "SALES_MANAGER", "SALES", "VIEWER")

                // ── Giá vốn & Biên lợi nhuận: Quản lý kinh doanh và Quản trị viên ──
                .requestMatchers("/api/finance/cost-price", "/api/finance/cost-price/**",
                        "/api/finance/profit-margin", "/api/finance/profit-margin/**")
                        .hasAnyRole("ADMIN", "SALES_MANAGER")

                // ── Tài chính khác (công nợ, quản lý): ADMIN, SALES_MANAGER, ACCOUNTANT ──
                .requestMatchers("/api/finance/**").hasAnyRole(
                        "ADMIN", "SALES_MANAGER", "ACCOUNTANT")

                // ── Báo cáo: mọi role đã đăng nhập ──
                // Chi tiết loại báo cáo dùng @PreAuthorize
                .requestMatchers("/api/reports/**").authenticated()

                .requestMatchers("/api/test/**").authenticated()

                // Cho phép /error để hiển thị đúng mã lỗi
                .requestMatchers("/error").permitAll()

                // Mọi chức năng khác đều kiểm quyền ở tầng server, mặc định là từ chối.
                .anyRequest().denyAll()
            )

            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
