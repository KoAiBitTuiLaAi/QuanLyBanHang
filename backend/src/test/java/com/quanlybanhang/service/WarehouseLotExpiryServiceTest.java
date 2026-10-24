package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.WarehouseVoucherItemRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.LotExpiryAlertResponse;
import com.quanlybanhang.dto.response.LotThresholdResponse;
import com.quanlybanhang.dto.response.WarehouseLotInventoryResponse;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WarehouseLotExpiryServiceTest - Nghiệp vụ Theo dõi Lô & Hạn sử dụng (S6-01)")
class WarehouseLotExpiryServiceTest {

    @Mock
    private WarehouseRepository warehouseRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private UnitConversionService unitConversionService;
    @Mock
    private WarehouseVoucherRepository voucherRepository;
    @Mock
    private WarehouseInventoryRepository inventoryRepository;
    @Mock
    private WarehouseLotInventoryRepository lotInventoryRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private WarehouseServiceImpl warehouseService;

    private Warehouse khoHcm;
    private Product suaTuoi;
    private WarehouseLotInventory lotConHan;
    private WarehouseLotInventory lotSapHetHan;
    private WarehouseLotInventory lotQuaHan;

    @BeforeEach
    void setUp() {
        authenticate("admin", "ROLE_ADMIN");
        khoHcm = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").active(true).build();

        suaTuoi = Product.builder()
                .id(10L)
                .code("SP-SUA01")
                .name("Sữa tươi tiệt trùng 1L")
                .unit("hộp")
                .price(new BigDecimal("35000"))
                .active(true)
                .manageLot(true)
                .expiryWarningDays(15) // Ngưỡng cảnh báo khai báo riêng cho SKU này là 15 ngày
                .build();

        LocalDate today = LocalDate.now();

        // Lô 1: Còn hạn xa (60 ngày nữa)
        lotConHan = WarehouseLotInventory.builder()
                .id(101L)
                .warehouse(khoHcm)
                .product(suaTuoi)
                .lotNumber("LOT-2026-01")
                .manufacturingDate(today.minusDays(10))
                .expiryDate(today.plusDays(60))
                .baseQuantity(new BigDecimal("100.0000"))
                .baseUnit("hộp")
                .build();

        // Lô 2: Sắp hết hạn (còn 7 ngày, nhỏ hơn ngưỡng 15 ngày của SKU)
        lotSapHetHan = WarehouseLotInventory.builder()
                .id(102L)
                .warehouse(khoHcm)
                .product(suaTuoi)
                .lotNumber("LOT-2026-02")
                .manufacturingDate(today.minusDays(50))
                .expiryDate(today.plusDays(7))
                .baseQuantity(new BigDecimal("50.0000"))
                .baseUnit("hộp")
                .build();

        // Lô 3: Đã quá hạn sử dụng (hết hạn cách đây 3 ngày)
        lotQuaHan = WarehouseLotInventory.builder()
                .id(103L)
                .warehouse(khoHcm)
                .product(suaTuoi)
                .lotNumber("LOT-2026-03")
                .manufacturingDate(today.minusDays(60))
                .expiryDate(today.minusDays(3))
                .baseQuantity(new BigDecimal("20.0000"))
                .baseUnit("hộp")
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String... permissions) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String perm : permissions) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "pass", authorities));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Mỗi lô có số lô, ngày sản xuất, hạn sử dụng và số lượng còn lại
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Xem danh sách tồn theo lô: Trả về đầy đủ số lô, NSX, HSD, số lượng còn lại và trạng thái")
    void getLotInventory_ReturnsLotInformationWithRemainingQuantityAndStatus() {
        when(lotInventoryRepository.findByWarehouseIdAndProductId(1L, 10L))
                .thenReturn(List.of(lotConHan, lotSapHetHan, lotQuaHan));

        List<WarehouseLotInventoryResponse> responses = warehouseService.getLotInventory(1L, 10L, null, null, null);

        assertThat(responses).hasSize(3);

        // Kiểm tra Lô quá hạn
        WarehouseLotInventoryResponse rQuaHan = responses.stream()
                .filter(r -> "LOT-2026-03".equals(r.getLotNumber())).findFirst().orElseThrow();
        assertThat(rQuaHan.getLotNumber()).isEqualTo("LOT-2026-03");
        assertThat(rQuaHan.getManufacturingDate()).isEqualTo(lotQuaHan.getManufacturingDate());
        assertThat(rQuaHan.getExpiryDate()).isEqualTo(lotQuaHan.getExpiryDate());
        assertThat(rQuaHan.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("20.0000"));
        assertThat(rQuaHan.getIsExpired()).isTrue();
        assertThat(rQuaHan.getStatus()).isEqualTo("EXPIRED");
        assertThat(rQuaHan.getDaysUntilExpiry()).isEqualTo(-3L);

        // Kiểm tra Lô sắp hết hạn
        WarehouseLotInventoryResponse rSapHetHan = responses.stream()
                .filter(r -> "LOT-2026-02".equals(r.getLotNumber())).findFirst().orElseThrow();
        assertThat(rSapHetHan.getIsExpiringSoon()).isTrue();
        assertThat(rSapHetHan.getStatus()).isEqualTo("EXPIRING_SOON");
        assertThat(rSapHetHan.getDaysUntilExpiry()).isEqualTo(7L);
        assertThat(rSapHetHan.getWarningThresholdDays()).isEqualTo(15);

        // Kiểm tra Lô còn hạn
        WarehouseLotInventoryResponse rConHan = responses.stream()
                .filter(r -> "LOT-2026-01".equals(r.getLotNumber())).findFirst().orElseThrow();
        assertThat(rConHan.getIsExpired()).isFalse();
        assertThat(rConHan.getIsExpiringSoon()).isFalse();
        assertThat(rConHan.getStatus()).isEqualTo("ACTIVE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Cảnh báo lô sắp hết hạn theo ngưỡng khai báo được
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cảnh báo lô sắp hết hạn: Lấy danh sách lô theo ngưỡng cấu hình của SKU (15 ngày)")
    void getLotExpiryAlerts_ReturnsExpiringAndExpiredLots_ExcludesHealthyLots() {
        when(lotInventoryRepository.findByWarehouseIdAndProductId(1L, 10L))
                .thenReturn(List.of(lotConHan, lotSapHetHan, lotQuaHan));

        LotExpiryAlertResponse alertResponse = warehouseService.getLotExpiryAlerts(1L, 10L, null, null);

        assertThat(alertResponse.getTotalAlerts()).isEqualTo(2); // lotSapHetHan (7 ngày <= 15) và lotQuaHan (-3 ngày)
        assertThat(alertResponse.getExpiredCount()).isEqualTo(1);
        assertThat(alertResponse.getExpiringSoonCount()).isEqualTo(1);
        assertThat(alertResponse.getItems()).extracting(WarehouseLotInventoryResponse::getLotNumber)
                .containsExactly("LOT-2026-03", "LOT-2026-02"); // Sắp xếp theo HSD tăng dần
    }

    @Test
    @DisplayName("Cảnh báo lô sắp hết hạn: Cho phép ghi đè ngưỡng cảnh báo tùy chọn qua tham số query (vd: 5 ngày)")
    void getLotExpiryAlerts_CustomThresholdOverridesSkuThreshold() {
        when(lotInventoryRepository.findByWarehouseIdAndProductId(1L, 10L))
                .thenReturn(List.of(lotConHan, lotSapHetHan, lotQuaHan));

        // Nếu khai báo ngưỡng 5 ngày: lotSapHetHan còn 7 ngày sẽ KHÔNG nằm trong cảnh báo sắp hết hạn
        LotExpiryAlertResponse alertResponse = warehouseService.getLotExpiryAlerts(1L, 10L, 5, null);

        assertThat(alertResponse.getTotalAlerts()).isEqualTo(1); // Chỉ còn lotQuaHan
        assertThat(alertResponse.getExpiredCount()).isEqualTo(1);
        assertThat(alertResponse.getExpiringSoonCount()).isEqualTo(0);
        assertThat(alertResponse.getItems().get(0).getLotNumber()).isEqualTo("LOT-2026-03");
    }

    @Test
    @DisplayName("Khai báo ngưỡng cảnh báo hạn dùng: Cập nhật ngưỡng số ngày cho SKU thành công")
    void updateLotThreshold_Success_UpdatesProductExpiryWarningDays() {
        when(productRepository.findById(10L)).thenReturn(Optional.of(suaTuoi));

        LotThresholdResponse response = warehouseService.updateLotThreshold(10L, 25, "warehouse.manager");

        assertThat(response.getProductId()).isEqualTo(10L);
        assertThat(response.getExpiryWarningDays()).isEqualTo(25);
        assertThat(suaTuoi.getExpiryWarningDays()).isEqualTo(25);
        verify(productRepository).save(suaTuoi);
        verify(auditLogService).logAction(eq("LOT_THRESHOLD"), eq("SP-SUA01"), eq("UPDATE_THRESHOLD"), isNull(), anyString(), eq("warehouse.manager"));
    }

    @Test
    @DisplayName("Khai báo ngưỡng cảnh báo hạn dùng: Giá trị nhỏ hơn 1 ngày bị chặn với lỗi rõ ràng")
    void updateLotThreshold_InvalidDays_ThrowsException() {
        assertThatThrownBy(() -> warehouseService.updateLotThreshold(10L, 0, "warehouse.manager"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lớn hơn hoặc bằng 1 ngày");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Không cho xuất lô đã quá hạn, trừ khi Quản lý kho xác nhận ghi đè kèm lý do
    // ─────────────────────────────────────────────────────────────────────────

    private void lockExportStock(WarehouseLotInventory lot) {
        when(lotInventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(List.of(lot));
        lenient().when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(
                WarehouseInventory.builder().warehouse(khoHcm).product(suaTuoi).baseQuantity(new BigDecimal("1000")).baseUnit("hộp").build()));
    }

    @Test
    @DisplayName("Xuất lô còn hạn: Nhân viên kho bình thường (WAREHOUSE_EXPORT) xuất thành công")
    void exportGoods_NonExpiredLot_StaffAllowed() {
        lockExportStock(lotConHan);
        authenticate("staff01", "ROLE_WAREHOUSE_STAFF", "WAREHOUSE_EXPORT");

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .lotNumber("LOT-2026-01") // Lô còn hạn
                        .quantity(new BigDecimal("10"))
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        WarehouseInventory totalInv = WarehouseInventory.builder()
                .warehouse(khoHcm).product(suaTuoi).baseQuantity(new BigDecimal("100.0000")).baseUnit("hộp").build();
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(totalInv));
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> i.getArgument(0));

        WarehouseVoucherResponse response = warehouseService.exportGoods(request, "staff01");

        assertThat(response).isNotNull();
        assertThat(lotConHan.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("90.0000")); // 100 - 10
        assertThat(totalInv.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("90.0000"));
    }

    @Test
    @DisplayName("Xuất lô đã quá hạn: Nhân viên kho không có quyền Quản lý kho -> Bị chặn 403 AccessDenied")
    void exportGoods_ExpiredLot_StaffWithoutManagerPermission_ThrowsAccessDenied() {
        lockExportStock(lotQuaHan);
        authenticate("staff01", "ROLE_WAREHOUSE_STAFF", "WAREHOUSE_EXPORT");

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .overrideExpiredLot(true) // Dù cố tình gửi cờ override
                .expiredLotOverrideReason("Thử xuất hàng hết hạn")
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .lotNumber("LOT-2026-03") // Lô đã hết hạn
                        .quantity(new BigDecimal("5"))
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        assertThatThrownBy(() -> warehouseService.exportGoods(request, "staff01"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Chỉ Quản lý kho mới có quyền xác nhận ghi đè xuất lô đã quá hạn");
    }

    @Test
    @DisplayName("Xuất lô đã quá hạn: Quản lý kho nhưng KHÔNG xác nhận cờ ghi đè -> Bị chặn yêu cầu xác nhận")
    void exportGoods_ExpiredLot_ManagerWithoutOverrideFlag_ThrowsException() {
        lockExportStock(lotQuaHan);
        authenticate("manager01", "ROLE_WAREHOUSE_MANAGER", "WAREHOUSE_EXPORT", "WAREHOUSE_APPROVE");

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .overrideExpiredLot(false) // Chưa xác nhận ghi đè
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .lotNumber("LOT-2026-03")
                        .quantity(new BigDecimal("5"))
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        assertThatThrownBy(() -> warehouseService.exportGoods(request, "manager01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cần Quản lý kho xác nhận ghi đè để xuất hàng");
    }

    @Test
    @DisplayName("Xuất lô đã quá hạn: Quản lý kho có xác nhận cờ ghi đè nhưng BỎ TRỐNG LÝ DO -> Bị chặn bắt buộc lý do")
    void exportGoods_ExpiredLot_ManagerWithoutReason_ThrowsException() {
        lockExportStock(lotQuaHan);
        authenticate("manager01", "ROLE_WAREHOUSE_MANAGER", "WAREHOUSE_EXPORT", "WAREHOUSE_APPROVE");

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .overrideExpiredLot(true)
                .expiredLotOverrideReason("   ") // Lý do rỗng
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .lotNumber("LOT-2026-03")
                        .quantity(new BigDecimal("5"))
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        assertThatThrownBy(() -> warehouseService.exportGoods(request, "manager01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bắt buộc phải nhập lý do khi Quản lý kho xác nhận ghi đè xuất lô đã quá hạn");
    }

    @Test
    @DisplayName("Xuất lô đã quá hạn: Quản lý kho có quyền WAREHOUSE_APPROVE xác nhận ghi đè kèm lý do -> Cho phép xuất và ghi audit log")
    void exportGoods_ExpiredLot_ManagerWithOverrideAndReason_AllowedAndAudited() {
        lockExportStock(lotQuaHan);
        authenticate("manager01", "ROLE_WAREHOUSE_MANAGER", "WAREHOUSE_EXPORT", "WAREHOUSE_APPROVE");

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .overrideExpiredLot(true)
                .expiredLotOverrideReason("Xuất hủy tiêu hủy theo biên bản kiểm kê")
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .lotNumber("LOT-2026-03")
                        .quantity(new BigDecimal("5"))
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        WarehouseInventory totalInv = WarehouseInventory.builder()
                .warehouse(khoHcm).product(suaTuoi).baseQuantity(new BigDecimal("20.0000")).baseUnit("hộp").build();
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(totalInv));
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> i.getArgument(0));

        WarehouseVoucherResponse response = warehouseService.exportGoods(request, "manager01");

        assertThat(response).isNotNull();
        assertThat(response.getOverrideExpiredLot()).isTrue();
        assertThat(response.getExpiredLotOverrideReason()).isEqualTo("Xuất hủy tiêu hủy theo biên bản kiểm kê");
        assertThat(lotQuaHan.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("15.0000")); // 20 - 5

        // Xác nhận đã ghi audit log hành động ghi đè
        verify(auditLogService).logAction(
                eq("WAREHOUSE_LOT_OVERRIDE"),
                eq("1:10:LOT-2026-03"),
                eq("EXPIRED_LOT_OVERRIDE"),
                isNull(),
                contains("Xuất hủy tiêu hủy"),
                eq("manager01")
        );
    }

    @Test
    @DisplayName("Xuất kho lô hàng: Số lượng yêu cầu xuất vượt quá số lượng tồn của lô -> Bị chặn báo lỗi tồn lô không đủ")
    void exportGoods_QuantityExceedsLotQuantity_ThrowsException() {
        lockExportStock(lotConHan);
        authenticate("staff01", "ROLE_WAREHOUSE_STAFF", "WAREHOUSE_EXPORT");

        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .lotNumber("LOT-2026-01")
                        .quantity(new BigDecimal("150")) // Tồn lô chỉ có 100
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(suaTuoi));
        when(unitConversionService.getConversionFactor(suaTuoi, "hộp")).thenReturn(BigDecimal.ONE);

        assertThatThrownBy(() -> warehouseService.exportGoods(request, "staff01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không đủ tồn kho theo lô để xuất");
    }
}

