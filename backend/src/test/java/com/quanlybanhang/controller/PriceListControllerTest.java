package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.request.PriceListItemRequest;
import com.quanlybanhang.dto.request.PriceListNewVersionRequest;
import com.quanlybanhang.dto.request.PriceListRequest;
import com.quanlybanhang.dto.response.PriceListItemResponse;
import com.quanlybanhang.dto.response.PriceListResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.exception.PriceListLockedException;
import com.quanlybanhang.model.CustomerGroup;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.JwtUtils;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.PriceListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PriceListController.class)
@Import({SecurityConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("PriceListController - API Quản lý Bảng giá theo nhóm khách hàng (S2-10)")
class PriceListControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @MockBean
    private PriceListService priceListService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private com.quanlybanhang.repository.UserRepository userRepository;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        org.mockito.Mockito.doAnswer(invocation -> {
            jakarta.servlet.ServletRequest request = invocation.getArgument(0);
            jakarta.servlet.ServletResponse response = invocation.getArgument(1);
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        this.mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("GET /api/price-lists trả về danh sách bảng giá cho Sales -> 200 OK")
    void getAllPriceLists_Authenticated_Returns200() throws Exception {
        PriceListResponse resp = PriceListResponse.builder()
                .id(1L)
                .code("BG-DLC1-2026")
                .name("Bảng giá Đại lý cấp 1")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .customerGroupName("Đại lý cấp 1")
                .build();

        when(priceListService.getAllPriceLists(any(), any(), any())).thenReturn(List.of(resp));

        mockMvc.perform(get("/api/price-lists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("BG-DLC1-2026"));
    }

    @Test
    @WithMockUser(username = "sales_mgr", roles = "SALES_MANAGER")
    @DisplayName("POST /api/price-lists bởi Quản lý kinh doanh -> 201 Created")
    void createPriceList_SalesManager_Returns201() throws Exception {
        PriceListRequest req = PriceListRequest.builder()
                .code("BG-DLC1-2026")
                .name("Bảng giá Đại lý cấp 1")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .items(List.of(PriceListItemRequest.builder()
                        .productId(10L)
                        .sellingPrice(new BigDecimal("16500"))
                        .floorPrice(new BigDecimal("15500"))
                        .build()))
                .build();

        PriceListResponse resp = PriceListResponse.builder()
                .id(1L)
                .code("BG-DLC1-2026")
                .name("Bảng giá Đại lý cấp 1")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .customerGroupName("Đại lý cấp 1")
                .items(List.of(PriceListItemResponse.builder()
                        .productId(10L)
                        .sellingPrice(new BigDecimal("16500"))
                        .floorPrice(new BigDecimal("15500"))
                        .build()))
                .build();

        when(priceListService.createPriceList(any(), any())).thenReturn(resp);

        mockMvc.perform(post("/api/price-lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.code").value("BG-DLC1-2026"))
                .andExpect(jsonPath("$.data.items[0].sellingPrice").value(16500));
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("POST /api/price-lists bởi Nhân viên kinh doanh -> 403 Forbidden (chỉ Sales Manager & Admin)")
    void createPriceList_Sales_Returns403() throws Exception {
        PriceListRequest req = PriceListRequest.builder()
                .code("BG-DLC1-2026")
                .name("Bảng giá Đại lý cấp 1")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .build();

        mockMvc.perform(post("/api/price-lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sales_mgr", roles = "SALES_MANAGER")
    @DisplayName("PUT /api/price-lists/{id} khi bảng giá đã phát sinh đơn -> 400 Bad Request (PriceListLockedException)")
    void updatePriceList_WhenLocked_Returns400() throws Exception {
        PriceListRequest req = PriceListRequest.builder()
                .code("BG-DLC1-2026")
                .name("Sửa bảng giá")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .build();

        when(priceListService.updatePriceList(eq(1L), any(), any()))
                .thenThrow(new PriceListLockedException("Bảng giá đã phát sinh đơn hàng, không được phép chỉnh sửa. Vui lòng tạo phiên bản mới."));

        mockMvc.perform(put("/api/price-lists/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Bảng giá đã phát sinh đơn hàng, không được phép chỉnh sửa. Vui lòng tạo phiên bản mới."));
    }

    @Test
    @WithMockUser(username = "sales_mgr", roles = "SALES_MANAGER")
    @DisplayName("POST /api/price-lists/{id}/new-version tạo phiên bản mới thành công -> 201 Created")
    void createNewVersion_SalesManager_Returns201() throws Exception {
        PriceListNewVersionRequest req = PriceListNewVersionRequest.builder().build();

        PriceListResponse resp = PriceListResponse.builder()
                .id(2L)
                .code("BG-DLC1-2026-V2")
                .name("Bảng giá Đại lý cấp 1 (v2)")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .version(2)
                .hasOrders(false)
                .build();

        when(priceListService.createNewVersion(eq(1L), any(), any())).thenReturn(resp);

        mockMvc.perform(post("/api/price-lists/1/new-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.code").value("BG-DLC1-2026-V2"))
                .andExpect(jsonPath("$.data.version").value(2));
    }
}
