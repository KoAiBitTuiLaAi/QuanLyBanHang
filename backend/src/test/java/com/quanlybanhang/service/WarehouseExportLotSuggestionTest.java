package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.WarehouseVoucherItemRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.LotSuggestionResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WarehouseService - Gợi ý lô xuất FEFO và xuất kho theo lô (S6-04)")
class WarehouseExportLotSuggestionTest {

    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UnitConversionService unitConversionService;
    @Mock private WarehouseLotInventoryRepository lotInventoryRepository;
    @Mock private WarehouseInventoryRepository inventoryRepository;
    @Mock private WarehouseVoucherRepository voucherRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private WarehouseServiceImpl warehouseService;

    private Warehouse khoHcm;
    private Product suaTuoi;
    private WarehouseLotInventory lotA; // HSD gần nhưng chỉ có 5
    private WarehouseLotInventory lotB; // HSD gần nhì và có 20
    private WarehouseLotInventory lotC; // HSD xa và có 50

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "", List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));

        khoHcm = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho Hồ Chí Minh").active(true).build();

        suaTuoi = Product.builder()
                .id(100L)
                .code("SP-SUA-100")
                .name("Sữa tươi tiệt trùng 1L")
                .unit("hộp")
                .manageLot(true)
                .price(new BigDecimal("35000"))
                .active(true)
                .build();

        lotA = WarehouseLotInventory.builder()
                .id(1L)
                .warehouse(khoHcm)
                .product(suaTuoi)
                .lotNumber("LOT-2026-A")
                .expiryDate(LocalDate.now().plusDays(10)) // HSD gần nhất (10 ngày)
                .manufacturingDate(LocalDate.now().minusDays(20))
                .baseQuantity(new BigDecimal("5.0000"))
                .baseUnit("hộp")
                .build();

        lotB = WarehouseLotInventory.builder()
                .id(2L)
                .warehouse(khoHcm)
                .product(suaTuoi)
                .lotNumber("LOT-2026-B")
                .expiryDate(LocalDate.now().plusDays(30)) // HSD nhì (30 ngày)
                .manufacturingDate(LocalDate.now().minusDays(10))
                .baseQuantity(new BigDecimal("20.0000"))
                .baseUnit("hộp")
                .build();

        lotC = WarehouseLotInventory.builder()
                .id(3L)
                .warehouse(khoHcm)
                .product(suaTuoi)
                .lotNumber("LOT-2026-C")
                .expiryDate(LocalDate.now().plusDays(90)) // HSD xa (90 ngày)
                .manufacturingDate(LocalDate.now().minusDays(5))
                .baseQuantity(new BigDecimal("50.0000"))
                .baseUnit("hộp")
                .build();
    }

    private void lockExportStock() {
        when(lotInventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 100L)).thenReturn(List.of(lotA, lotB, lotC));
        lenient().when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 100L)).thenReturn(Optional.of(
                WarehouseInventory.builder().warehouse(khoHcm).product(suaTuoi).baseQuantity(new BigDecimal("75")).baseUnit("hộp").build()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC1: Mặc định gợi ý lô có hạn sử dụng gần nhất còn đủ số lượng (FEFO)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FEFO: Khi cần 10 hộp, Lot A (5 hộp) không đủ -> gợi ý Lot B (20 hộp) có HSD gần nhất trong các lô đủ hàng")
    void suggestLot_Need10_SkipsLotA_SuggestsLotB() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(100L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);
        when(lotInventoryRepository.findByWarehouseIdAndProductId(1L, 100L)).thenReturn(List.of(lotA, lotB, lotC));

        LotSuggestionResponse resp = warehouseService.suggestLotForExport(1L, 100L, new BigDecimal("10"), "hộp");

        assertThat(resp).isNotNull();
        assertThat(resp.getHasSufficientStock()).isTrue();
        assertThat(resp.getSuggestedLot()).isNotNull();
        assertThat(resp.getSuggestedLot().getLotNumber()).isEqualTo("LOT-2026-B");
        assertThat(resp.getSuggestedLot().getAvailableQuantity()).isEqualByComparingTo("20");
        assertThat(resp.getAvailableLots()).hasSize(3);
        // Sắp xếp FEFO: Lot A -> Lot B -> Lot C
        assertThat(resp.getAvailableLots().get(0).getLotNumber()).isEqualTo("LOT-2026-A");
        assertThat(resp.getAvailableLots().get(0).getSufficientForRequest()).isFalse();
        assertThat(resp.getAvailableLots().get(1).getLotNumber()).isEqualTo("LOT-2026-B");
        assertThat(resp.getAvailableLots().get(1).getSufficientForRequest()).isTrue();
    }

    @Test
    @DisplayName("FEFO: Khi cần 3 hộp, Lot A (5 hộp) đủ số lượng và có HSD gần nhất -> gợi ý Lot A")
    void suggestLot_Need3_SuggestsLotA() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(100L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);
        when(lotInventoryRepository.findByWarehouseIdAndProductId(1L, 100L)).thenReturn(List.of(lotA, lotB, lotC));

        LotSuggestionResponse resp = warehouseService.suggestLotForExport(1L, 100L, new BigDecimal("3"), "hộp");

        assertThat(resp).isNotNull();
        assertThat(resp.getHasSufficientStock()).isTrue();
        assertThat(resp.getSuggestedLot().getLotNumber()).isEqualTo("LOT-2026-A");
        assertThat(resp.getSuggestedLot().getSufficientForRequest()).isTrue();
    }

    @Test
    @DisplayName("FEFO: Khi cần 100 hộp nhưng không có lô nào đủ -> suggestedLot là null, hasSufficientStock = false")
    void suggestLot_Need100_NoSingleLotSufficient() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(100L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);
        when(lotInventoryRepository.findByWarehouseIdAndProductId(1L, 100L)).thenReturn(List.of(lotA, lotB, lotC));

        LotSuggestionResponse resp = warehouseService.suggestLotForExport(1L, 100L, new BigDecimal("100"), "hộp");

        assertThat(resp.getHasSufficientStock()).isFalse();
        assertThat(resp.getSuggestedLot()).isNull();
        assertThat(resp.getMessage()).contains("Không có lô đơn lẻ nào còn đủ số lượng yêu cầu");
        assertThat(resp.getAvailableLots()).hasSize(3);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC2: Thủ kho chọn lô khác được nhưng phải nhập lý do
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Xuất kho: Không truyền lotNumber -> tự động chọn lô gợi ý FEFO (Lot B khi cần 10 hộp) và không cần lý do")
    void exportGoods_NoLotNumberProvided_AutoPicksFefoLot() {
        lockExportStock();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(100L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        WarehouseInventory totalInventory = WarehouseInventory.builder()
                .warehouse(khoHcm).product(suaTuoi).baseQuantity(new BigDecimal("75")).baseUnit("hộp").build();
        lenient().when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 100L))
                .thenReturn(Optional.of(totalInventory));
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> i.getArgument(0));

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .voucherCode("PXK-AUTO-01")
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(100L)
                        .quantity(new BigDecimal("10"))
                        .unit("hộp")
                        // lotNumber để trống -> auto FEFO
                        .build()))
                .build();

        WarehouseVoucherResponse resp = warehouseService.exportGoods(request, "thukho01");

        assertThat(resp.getItems()).hasSize(1);
        var item = resp.getItems().get(0);
        assertThat(item.getLotNumber()).isEqualTo("LOT-2026-B");
        assertThat(item.getSuggestedLotNumber()).isEqualTo("LOT-2026-B");
        assertThat(item.getOverrideReason()).isNull();

        // Kiểm tra trừ đúng tồn của Lot B: 20 - 10 = 10
        assertThat(lotB.getBaseQuantity()).isEqualByComparingTo("10");
        // Kiểm tra trừ tồn tổng: 75 - 10 = 65
        assertThat(totalInventory.getBaseQuantity()).isEqualByComparingTo("65");
    }

    @Test
    @DisplayName("Xuất kho: Chọn đúng lô gợi ý (Lot B) -> thành công không cần nhập lý do")
    void exportGoods_SelectSameAsSuggestedLot_SuccessWithoutReason() {
        lockExportStock();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(100L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        WarehouseInventory totalInventory = WarehouseInventory.builder()
                .warehouse(khoHcm).product(suaTuoi).baseQuantity(new BigDecimal("75")).baseUnit("hộp").build();
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 100L))
                .thenReturn(Optional.of(totalInventory));
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> i.getArgument(0));

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .voucherCode("PXK-SAME-01")
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(100L)
                        .quantity(new BigDecimal("10"))
                        .unit("hộp")
                        .lotNumber("LOT-2026-B") // Chọn đúng lô gợi ý
                        .build()))
                .build();

        WarehouseVoucherResponse resp = warehouseService.exportGoods(request, "thukho01");
        assertThat(resp.getItems().get(0).getLotNumber()).isEqualTo("LOT-2026-B");
        assertThat(resp.getItems().get(0).getOverrideReason()).isNull();
    }

    @Test
    @DisplayName("Xuất kho: Chọn lô khác (Lot C) nhưng KHÔNG nhập lý do -> ném ngoại lệ yêu cầu lý do")
    void exportGoods_SelectDifferentLotWithoutReason_ThrowsException() {
        lockExportStock();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(100L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(100L)
                        .quantity(new BigDecimal("10"))
                        .unit("hộp")
                        .lotNumber("LOT-2026-C") // Khác LOT-2026-B (lô gợi ý)
                        // Không có overrideReason hoặc note
                        .build()))
                .build();

        assertThatThrownBy(() -> warehouseService.exportGoods(request, "thukho01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bắt buộc phải nhập lý do");
    }

    @Test
    @DisplayName("Xuất kho: Chọn lô khác (Lot C) và CÓ nhập lý do -> thành công, lưu cả lô gợi ý và lý do")
    void exportGoods_SelectDifferentLotWithReason_SuccessAndRecordsReason() {
        lockExportStock();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(100L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        WarehouseInventory totalInventory = WarehouseInventory.builder()
                .warehouse(khoHcm).product(suaTuoi).baseQuantity(new BigDecimal("75")).baseUnit("hộp").build();
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 100L))
                .thenReturn(Optional.of(totalInventory));
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> i.getArgument(0));

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .voucherCode("PXK-DIFF-REASON-01")
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(100L)
                        .quantity(new BigDecimal("10"))
                        .unit("hộp")
                        .lotNumber("LOT-2026-C")
                        .overrideReason("Khách hàng chỉ định lấy lô date xa để chuyển đi tỉnh")
                        .build()))
                .build();

        WarehouseVoucherResponse resp = warehouseService.exportGoods(request, "thukho01");

        var item = resp.getItems().get(0);
        assertThat(item.getLotNumber()).isEqualTo("LOT-2026-C");
        assertThat(item.getSuggestedLotNumber()).isEqualTo("LOT-2026-B");
        assertThat(item.getOverrideReason()).isEqualTo("Khách hàng chỉ định lấy lô date xa để chuyển đi tỉnh");

        // Trừ đúng tồn của Lot C: 50 - 10 = 40
        assertThat(lotC.getBaseQuantity()).isEqualByComparingTo("40");
        // Lot B giữ nguyên: 20
        assertThat(lotB.getBaseQuantity()).isEqualByComparingTo("20");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC3: Xuất kho trừ đúng tồn của lô đã chọn & chặn xuất âm lô
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Xuất kho vượt quá tồn của lô đã chọn -> ném ngoại lệ không đủ tồn lô")
    void exportGoods_ExceedLotStock_ThrowsException() {
        lockExportStock();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(100L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        WarehouseInventory totalInventory = WarehouseInventory.builder()
                .warehouse(khoHcm).product(suaTuoi).baseQuantity(new BigDecimal("75")).baseUnit("hộp").build();
        lenient().when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 100L))
                .thenReturn(Optional.of(totalInventory));

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(100L)
                        .quantity(new BigDecimal("10")) // Yêu cầu 10 từ Lot A
                        .unit("hộp")
                        .lotNumber("LOT-2026-A")
                        .overrideReason("Cố tình chọn lot A")
                        .build()))
                .build();

        assertThatThrownBy(() -> warehouseService.exportGoods(request, "thukho01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không đủ tồn kho để xuất");
    }

    @Test
    @DisplayName("Xuất kho chọn lô không tồn tại trong kho -> ném ngoại lệ")
    void exportGoods_NonExistentLot_ThrowsException() {
        lockExportStock();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(100L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(100L)
                        .quantity(new BigDecimal("10"))
                        .unit("hộp")
                        .lotNumber("LOT-UNKNOWN")
                        .overrideReason("Lô lạ")
                        .build()))
                .build();

        assertThatThrownBy(() -> warehouseService.exportGoods(request, "thukho01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không tìm thấy lô 'LOT-UNKNOWN'");
    }
}

