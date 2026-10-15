package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.service.WarehouseService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class WarehouseControllerTest {
    @Mock WarehouseService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WarehouseController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"adjust", "import", "export"})
    void emptyOrInvalidVoucherNeverReportsSuccess(String endpoint) throws Exception {
        for (String body : new String[]{"", "null", "{}", "{\"items\":[]}", "{\"items\":[null]}",
                "{\"items\":[{\"productId\":1,\"quantity\":0}]}"}) {
            mvc.perform(post("/api/warehouse/" + endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        }
        verifyNoInteractions(service);
    }

    @Test
    void validImportReturnsThePersistedVoucher() throws Exception {
        when(service.importGoods(any(WarehouseVoucherRequest.class), eq("system")))
                .thenReturn(WarehouseVoucherResponse.builder().id(87L).voucherCode("PNK-87").build());
        mvc.perform(post("/api/warehouse/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":3,\"items\":[{\"productId\":7,\"quantity\":2}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(87))
                .andExpect(jsonPath("$.data.voucherCode").value("PNK-87"));
        verify(service).importGoods(any(WarehouseVoucherRequest.class), eq("system"));
    }

    @Test
    void createImportDraft_ReturnsDraftVoucher() throws Exception {
        when(service.importGoods(any(WarehouseVoucherRequest.class), eq("system")))
                .thenReturn(WarehouseVoucherResponse.builder().id(88L).voucherCode("PNK-DRAFT-88").status("DRAFT").build());
        mvc.perform(post("/api/warehouse/import/draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":3,\"items\":[{\"productId\":7,\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void confirmVoucher_ReturnsConfirmedVoucher() throws Exception {
        when(service.confirmVoucher(eq(88L), eq("system")))
                .thenReturn(WarehouseVoucherResponse.builder().id(88L).status("CONFIRMED").build());
        mvc.perform(post("/api/warehouse/vouchers/88/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void updateDraftVoucher_ReturnsUpdatedVoucher() throws Exception {
        when(service.updateVoucher(eq(88L), any(WarehouseVoucherRequest.class), eq("system")))
                .thenReturn(WarehouseVoucherResponse.builder().id(88L).note("Updated note").build());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/warehouse/vouchers/88")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":3,\"note\":\"Updated note\",\"items\":[{\"productId\":7,\"quantity\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.note").value("Updated note"));
    }

    @Test
    void deleteDraftVoucher_ReturnsSuccess() throws Exception {
        doNothing().when(service).deleteVoucher(eq(88L), eq("system"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/warehouse/vouchers/88"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void adjustVoucher_ReturnsAdjustmentVoucher() throws Exception {
        when(service.adjustVoucher(eq(88L), any(WarehouseVoucherRequest.class), eq("system")))
                .thenReturn(WarehouseVoucherResponse.builder()
                        .id(89L)
                        .type(com.quanlybanhang.model.WarehouseVoucherType.ADJUSTMENT)
                        .referenceVoucherId(88L)
                        .build());
        mvc.perform(post("/api/warehouse/vouchers/88/adjust").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adjustmentReason\":\"Lỗi bể vỡ\",\"items\":[{\"productId\":7,\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.data.referenceVoucherId").value(88));
    }

    @Test
    void getLotInventory_ReturnsLotList() throws Exception {
        when(service.getLotInventory(eq(1L), eq(7L)))
                .thenReturn(List.of(com.quanlybanhang.dto.response.WarehouseLotInventoryResponse.builder()
                        .lotNumber("LOT-A")
                        .baseQuantity(new java.math.BigDecimal("50"))
                        .build()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/warehouse/inventory/lots")
                        .param("warehouseId", "1")
                        .param("productId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lotNumber").value("LOT-A"))
                .andExpect(jsonPath("$.data[0].baseQuantity").value(50));
    }
}
