package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanlybanhang.dto.request.CreateDeliveryTripRequest;
import com.quanlybanhang.dto.request.CreateDeliveryTripStopRequest;
import com.quanlybanhang.dto.request.ReturnStopGoodsRequest;
import com.quanlybanhang.dto.request.UpdateDeliveryStopStatusRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.DeliveryStopStatus;
import com.quanlybanhang.model.DeliveryTripStatus;
import com.quanlybanhang.service.DeliveryTripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryTripControllerTest - Kiểm thử REST Endpoints Chuyến giao hàng (S6-07)")
class DeliveryTripControllerTest {

    @Mock
    private DeliveryTripService deliveryTripService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        mockMvc = MockMvcBuilders.standaloneSetup(new DeliveryTripController(deliveryTripService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/warehouse/delivery-trips/options: Trả về options và canManage")
    void getOptions_Returns200() throws Exception {
        DeliveryTripOptionsResponse options = DeliveryTripOptionsResponse.builder()
                .warehouses(List.of(new NamedResourceResponse(1L, "KHO-HN", "Kho Hà Nội")))
                .canManage(true)
                .build();

        when(deliveryTripService.getOptions()).thenReturn(options);

        mockMvc.perform(get("/api/warehouse/delivery-trips/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.warehouses[0].code").value("KHO-HN"))
                .andExpect(jsonPath("$.data.canManage").value(true));
    }

    @Test
    @DisplayName("GET /api/warehouse/delivery-trips: Tìm kiếm chuyến giao hàng phân trang")
    void searchTrips_Returns200() throws Exception {
        DeliveryTripResponse trip = DeliveryTripResponse.builder()
                .id(1L)
                .tripCode("CG-20261022-001")
                .status(DeliveryTripStatus.IN_PROGRESS)
                .totalPoints(2)
                .deliveredPoints(1L)
                .remainingPoints(1L)
                .totalValue(new BigDecimal("40000000.00"))
                .remainingValue(new BigDecimal("25000000.00"))
                .isOverdue(false)
                .build();

        when(deliveryTripService.searchTrips(any(), any(), any(), any(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(trip), org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/warehouse/delivery-trips")
                        .param("warehouseId", "1")
                        .param("status", "IN_PROGRESS")
                        .param("isOverdue", "false")
                        .param("page", "0")
                        .param("size", "20"))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].tripCode").value("CG-20261022-001"))
                .andExpect(jsonPath("$.data.content[0].totalPoints").value(2))
                .andExpect(jsonPath("$.data.content[0].remainingPoints").value(1))
                .andExpect(jsonPath("$.data.content[0].remainingValue").value(25000000.00));
    }

    @Test
    @DisplayName("GET /api/warehouse/delivery-trips/summary: Thống kê tiền hàng trên đường và cảnh báo")
    void getSummary_Returns200() throws Exception {
        DeliveryTripSummaryResponse summary = DeliveryTripSummaryResponse.builder()
                .totalRunningTrips(3L)
                .totalOverdueTrips(1L)
                .totalValueOnRoad(new BigDecimal("120000000.00"))
                .totalDeliveredValue(new BigDecimal("50000000.00"))
                .totalTripValue(new BigDecimal("170000000.00"))
                .totalPoints(10L)
                .totalDeliveredPoints(4L)
                .totalRemainingPoints(6L)
                .build();

        when(deliveryTripService.getSummary(eq(1L))).thenReturn(summary);

        mockMvc.perform(get("/api/warehouse/delivery-trips/summary")
                        .param("warehouseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalRunningTrips").value(3))
                .andExpect(jsonPath("$.data.totalOverdueTrips").value(1))
                .andExpect(jsonPath("$.data.totalValueOnRoad").value(120000000.00));
    }

    @Test
    @DisplayName("POST /api/warehouse/delivery-trips: Tạo mới chuyến giao hàng hợp lệ")
    void createTrip_Valid_Returns200() throws Exception {
        CreateDeliveryTripRequest req = CreateDeliveryTripRequest.builder()
                .warehouseId(1L)
                .driverName("Tài xế Hoàng")
                .vehiclePlate("29C-555.55")
                .expectedCompletionTime(LocalDateTime.now().plusHours(4))
                .stops(List.of(
                        CreateDeliveryTripStopRequest.builder()
                                .deliveryAddress("10 Tràng Tiền, Hoàn Kiếm")
                                .deliveryAmount(new BigDecimal("30000000.00"))
                                .build()
                ))
                .build();

        DeliveryTripResponse response = DeliveryTripResponse.builder()
                .id(5L)
                .tripCode("CG-20261022-005")
                .status(DeliveryTripStatus.IN_PROGRESS)
                .totalPoints(1)
                .totalValue(new BigDecimal("30000000.00"))
                .build();

        when(deliveryTripService.createTrip(any(CreateDeliveryTripRequest.class), any())).thenReturn(response);

        mockMvc.perform(post("/api/warehouse/delivery-trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tripCode").value("CG-20261022-005"));
    }

    @Test
    @DisplayName("POST /api/warehouse/delivery-trips: Thiếu driverName hoặc vehiclePlate -> 400 Bad Request")
    void createTrip_MissingRequiredFields_Returns400() throws Exception {
        CreateDeliveryTripRequest req = CreateDeliveryTripRequest.builder()
                .warehouseId(1L)
                // driverName trống
                // vehiclePlate trống
                .expectedCompletionTime(LocalDateTime.now().plusHours(4))
                .build();

        mockMvc.perform(post("/api/warehouse/delivery-trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/warehouse/delivery-trips/{id}/stops/{stopId}/status: Cập nhật trạng thái điểm giao")
    void updateStopStatus_Returns200() throws Exception {
        UpdateDeliveryStopStatusRequest req = UpdateDeliveryStopStatusRequest.builder()
                .status(DeliveryStopStatus.DELIVERED)
                .note("Đã nhận đủ hàng")
                .build();

        DeliveryTripResponse response = DeliveryTripResponse.builder()
                .id(1L)
                .tripCode("CG-20261022-001")
                .deliveredPoints(1L)
                .build();

        when(deliveryTripService.updateStopStatus(eq(1L), eq(101L), any(UpdateDeliveryStopStatusRequest.class), any()))
                .thenReturn(response);

        mockMvc.perform(put("/api/warehouse/delivery-trips/1/stops/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deliveredPoints").value(1));
    }

    @Test
    @DisplayName("POST /api/warehouse/delivery-trips/{id}/complete: Hoàn tất chuyến giao hàng")
    void completeTrip_Returns200() throws Exception {
        DeliveryTripResponse response = DeliveryTripResponse.builder()
                .id(1L)
                .status(DeliveryTripStatus.COMPLETED)
                .build();

        when(deliveryTripService.completeTrip(eq(1L), any())).thenReturn(response);

        mockMvc.perform(post("/api/warehouse/delivery-trips/1/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST /api/warehouse/delivery-trips/{id}/stops/{stopId}/return-to-warehouse: Nhập kho trả hàng tường minh")
    void returnStopGoods_Returns200() throws Exception {
        ReturnStopGoodsRequest req = ReturnStopGoodsRequest.builder()
                .returnReason("Đã nhập lại kho 5 thùng hàng")
                .build();

        DeliveryTripStopResponse stopResponse = DeliveryTripStopResponse.builder()
                .id(101L)
                .returnedToWarehouse(true)
                .build();

        when(deliveryTripService.returnStopGoodsToWarehouse(eq(1L), eq(101L), any(ReturnStopGoodsRequest.class), any()))
                .thenReturn(stopResponse);

        mockMvc.perform(post("/api/warehouse/delivery-trips/1/stops/101/return-to-warehouse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.returnedToWarehouse").value(true));
    }
}
