package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.WarehouseTransferItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferReceiveItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferReceiveRequest;
import com.quanlybanhang.dto.request.WarehouseTransferRequest;
import com.quanlybanhang.dto.response.WarehouseTransferItemResponse;
import com.quanlybanhang.dto.response.WarehouseTransferResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.WarehouseTransferStatus;
import com.quanlybanhang.service.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("WarehouseTransferController - Kiểm thử các endpoint chuyển kho nội bộ (S5-07)")
class WarehouseTransferControllerTest {

    @Mock
    private WarehouseService warehouseService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WarehouseController(warehouseService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Tạo phiếu chuyển kho thành công trả về HTTP 200")
    void createTransfer_ValidRequest_Returns200() throws Exception {
        WarehouseTransferResponse mockResponse = WarehouseTransferResponse.builder()
                .id(10L)
                .transferCode("PCK-20261015001")
                .fromWarehouseId(1L)
                .toWarehouseId(2L)
                .status(WarehouseTransferStatus.IN_TRANSIT)
                .statusDescription("Đang trên đường")
                .items(List.of(WarehouseTransferItemResponse.builder()
                        .productId(5L)
                        .quantity(new BigDecimal("10"))
                        .build()))
                .build();

        when(warehouseService.createTransfer(any(WarehouseTransferRequest.class), eq("system")))
                .thenReturn(mockResponse);

        String json = """
                {
                  "fromWarehouseId": 1,
                  "toWarehouseId": 2,
                  "note": "Chuyển kho kiểm thử",
                  "items": [
                    {
                      "productId": 5,
                      "quantity": 10,
                      "unit": "thùng"
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/warehouse/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transferCode").value("PCK-20261015001"))
                .andExpect(jsonPath("$.data.status").value("IN_TRANSIT"));

        verify(warehouseService).createTransfer(any(WarehouseTransferRequest.class), eq("system"));
    }

    @Test
    @DisplayName("Tạo phiếu chuyển kho thiếu kho xuất hoặc kho nhập -> 400 Bad Request")
    void createTransfer_MissingWarehouse_Returns400() throws Exception {
        String json = """
                {
                  "fromWarehouseId": null,
                  "toWarehouseId": 2,
                  "items": [
                    {
                      "productId": 5,
                      "quantity": 10
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/warehouse/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(warehouseService);
    }

    @Test
    @DisplayName("Tạo phiếu chuyển kho danh sách hàng rỗng -> 400 Bad Request")
    void createTransfer_EmptyItems_Returns400() throws Exception {
        String json = """
                {
                  "fromWarehouseId": 1,
                  "toWarehouseId": 2,
                  "items": []
                }
                """;

        mockMvc.perform(post("/api/warehouse/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(warehouseService);
    }

    @Test
    @DisplayName("Kho đến xác nhận nhận hàng trả về HTTP 200")
    void receiveTransfer_Success_Returns200() throws Exception {
        WarehouseTransferResponse mockResponse = WarehouseTransferResponse.builder()
                .id(10L)
                .status(WarehouseTransferStatus.COMPLETED)
                .statusDescription("Đã nhận hàng")
                .build();

        when(warehouseService.receiveTransfer(eq(10L), any(), eq("system")))
                .thenReturn(mockResponse);

        String json = """
                {
                  "note": "Kho đến đã nhận đủ",
                  "items": [
                    {
                      "itemId": 1,
                      "receivedQuantity": 10
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/warehouse/transfers/10/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(warehouseService).receiveTransfer(eq(10L), any(), eq("system"));
    }

    @Test
    @DisplayName("Lấy danh sách phiếu chuyển kho thành công")
    void getTransfers_ReturnsList() throws Exception {
        when(warehouseService.getTransfers(null, null, null))
                .thenReturn(List.of(WarehouseTransferResponse.builder().id(10L).build()));

        mockMvc.perform(get("/api/warehouse/transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("Hủy phiếu chuyển kho thành công")
    void cancelTransfer_Returns200() throws Exception {
        WarehouseTransferResponse mockResponse = WarehouseTransferResponse.builder()
                .id(10L)
                .status(WarehouseTransferStatus.CANCELLED)
                .statusDescription("Đã hủy")
                .build();

        when(warehouseService.cancelTransfer(eq(10L), eq("Hủy chuyển"), eq("system")))
                .thenReturn(mockResponse);

        mockMvc.perform(post("/api/warehouse/transfers/10/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Hủy chuyển\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}

