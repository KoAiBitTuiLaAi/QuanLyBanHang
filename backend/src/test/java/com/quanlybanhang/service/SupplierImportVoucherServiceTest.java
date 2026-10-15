package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.WarehouseVoucherItemRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.WarehouseLotInventoryResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("S5-04: Lập phiếu nhập kho từ nhà cung cấp, quản lý lô & hạn sử dụng, nháp / xác nhận / điều chỉnh")
class SupplierImportVoucherServiceTest {

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UnitConversionRepository unitConversionRepository;

    @Mock
    private UnitConversionService unitConversionService;

    @Mock
    private WarehouseVoucherRepository voucherRepository;

    @Mock
    private WarehouseInventoryRepository inventoryRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private WarehouseLotInventoryRepository lotInventoryRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private WarehouseServiceImpl warehouseService;

    private Warehouse khoHcm;
    private Supplier nhaCungCapBia;
    private Product biaKenManageLot;
    private Product snackKhongManageLot;

    @BeforeEach
    void setUp() {
        khoHcm = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho Tổng HCM").active(true).build();

        nhaCungCapBia = Supplier.builder()
                .id(5L)
                .code("NCC-HEINEKEN")
                .name("Công ty TNHH Heineken Việt Nam")
                .active(true)
                .build();

        // Sản phẩm có quản lý lô & hạn sử dụng
        biaKenManageLot = Product.builder()
                .id(10L)
                .code("SP-KEN330")
                .name("Bia Heineken Sleek lon 330ml")
                .unit("lon")
                .price(new BigDecimal("19000"))
                .costPrice(new BigDecimal("15000"))
                .manageLot(true)
                .active(true)
                .build();

        // Sản phẩm không quản lý lô
        snackKhongManageLot = Product.builder()
                .id(20L)
                .code("SP-SNACK-OISHI")
                .name("Snack Oishi tôm cay")
                .unit("gói")
                .price(new BigDecimal("6000"))
                .manageLot(false)
                .active(true)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // YÊU CẦU 1 & 2: KHAI BÁO NCC, SỐ CHỨNG TỪ, NGÀY NHẬP, KHO NHẬP & QUY ĐỔI ĐƠN VỊ
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Lập phiếu nhập kho từ NCC: khai báo đúng NCC, chứng từ, ngày nhập, quy đổi thùng về lon")
    void createSupplierImportVoucher_Success() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(nhaCungCapBia));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaKenManageLot));
        when(unitConversionService.getConversionFactor(biaKenManageLot, "thùng"))
                .thenReturn(new BigDecimal("24.0000"));
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(Optional.empty());
        when(lotInventoryRepository.findByWarehouseIdAndProductIdAndLotNumber(1L, 10L, "LOT-KEN-202610"))
                .thenReturn(Optional.empty());
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> {
            WarehouseVoucher v = i.getArgument(0);
            v.setId(100L);
            return v;
        });

        LocalDateTime importDate = LocalDateTime.of(2026, 10, 15, 9, 30);
        LocalDate expiryDate = LocalDate.of(2027, 10, 15);
        LocalDate mfgDate = LocalDate.of(2026, 10, 1);

        WarehouseVoucherRequest req = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .supplierId(5L)
                .documentNumber("HD-HEINEKEN-98765")
                .voucherDate(importDate)
                .items(List.of(
                        WarehouseVoucherItemRequest.builder()
                                .productId(10L)
                                .unit("thùng")
                                .quantity(new BigDecimal("10"))
                                .lotNumber("LOT-KEN-202610")
                                .expiryDate(expiryDate)
                                .manufacturingDate(mfgDate)
                                .price(new BigDecimal("420000"))
                                .build()
                ))
                .build();

        WarehouseVoucherResponse resp = warehouseService.importGoods(req, "staff_thukho");

        assertThat(resp).isNotNull();
        assertThat(resp.getSupplierId()).isEqualTo(5L);
        assertThat(resp.getSupplierName()).isEqualTo("Công ty TNHH Heineken Việt Nam");
        assertThat(resp.getDocumentNumber()).isEqualTo("HD-HEINEKEN-98765");
        assertThat(resp.getVoucherDate()).isEqualTo(importDate);
        assertThat(resp.getStatus()).isEqualTo("RECORDED");
        assertThat(resp.getItems()).hasSize(1);

        // Kiểm tra quy đổi đơn vị cơ sở: 10 thùng * 24 = 240 lon
        assertThat(resp.getItems().get(0).getUnit()).isEqualTo("thùng");
        assertThat(resp.getItems().get(0).getQuantity()).isEqualByComparingTo("10");
        assertThat(resp.getItems().get(0).getConversionFactor()).isEqualByComparingTo("24.0000");
        assertThat(resp.getItems().get(0).getBaseUnit()).isEqualTo("lon");
        assertThat(resp.getItems().get(0).getBaseQuantity()).isEqualByComparingTo("240.0000");
        assertThat(resp.getItems().get(0).getLotNumber()).isEqualTo("LOT-KEN-202610");
        assertThat(resp.getItems().get(0).getExpiryDate()).isEqualTo(expiryDate);
        assertThat(resp.getItems().get(0).getManufacturingDate()).isEqualTo(mfgDate);

        // Kiểm tra tồn kho được cộng
        verify(inventoryRepository).save(argThat(inv -> inv.getBaseQuantity().compareTo(new BigDecimal("240.0000")) == 0));
        // Kiểm tra tồn lô được cộng
        verify(lotInventoryRepository).save(argThat(lot -> lot.getBaseQuantity().compareTo(new BigDecimal("240.0000")) == 0
                && "LOT-KEN-202610".equals(lot.getLotNumber())));
    }

    @Test
    @DisplayName("Lập phiếu với nhà cung cấp đã ngừng hoạt động -> Báo lỗi")
    void createImportVoucher_InactiveSupplier_ThrowsException() {
        Supplier inactiveSupplier = Supplier.builder().id(6L).name("NCC Cũ").active(false).build();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(supplierRepository.findById(6L)).thenReturn(Optional.of(inactiveSupplier));

        WarehouseVoucherRequest req = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .supplierId(6L)
                .items(List.of(WarehouseVoucherItemRequest.builder().productId(10L).quantity(BigDecimal.ONE).build()))
                .build();

        assertThatThrownBy(() -> warehouseService.importGoods(req, "staff"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đã ngừng hoạt động");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // YÊU CẦU 3: GHI NHẬN SỐ LÔ VÀ HẠN SỬ DỤNG CHO MẶT HÀNG CÓ QUẢN LÝ LÔ
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sản phẩm có quản lý lô (manageLot=true) nhưng thiếu số lô -> Báo lỗi")
    void manageLotProduct_MissingLotNumber_ThrowsException() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaKenManageLot));
        when(unitConversionService.getConversionFactor(biaKenManageLot, "lon")).thenReturn(BigDecimal.ONE);

        WarehouseVoucherRequest req = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .quantity(new BigDecimal("50"))
                        .lotNumber(null) // THIẾU SỐ LÔ
                        .expiryDate(LocalDate.of(2027, 1, 1))
                        .build()))
                .build();

        assertThatThrownBy(() -> warehouseService.importGoods(req, "staff"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("có quản lý lô, bắt buộc phải nhập số lô");
    }

    @Test
    @DisplayName("Sản phẩm có quản lý lô (manageLot=true) nhưng thiếu hạn sử dụng -> Báo lỗi")
    void manageLotProduct_MissingExpiryDate_ThrowsException() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaKenManageLot));
        when(unitConversionService.getConversionFactor(biaKenManageLot, "lon")).thenReturn(BigDecimal.ONE);

        WarehouseVoucherRequest req = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .quantity(new BigDecimal("50"))
                        .lotNumber("LOT-202610")
                        .expiryDate(null) // THIẾU HSD
                        .build()))
                .build();

        assertThatThrownBy(() -> warehouseService.importGoods(req, "staff"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("có quản lý lô, bắt buộc phải nhập hạn sử dụng");
    }

    @Test
    @DisplayName("Sản phẩm có quản lý lô: Ngày sản xuất sau hạn sử dụng -> Báo lỗi")
    void manageLotProduct_MfgDateAfterExpiryDate_ThrowsException() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaKenManageLot));
        when(unitConversionService.getConversionFactor(biaKenManageLot, "lon")).thenReturn(BigDecimal.ONE);

        WarehouseVoucherRequest req = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .quantity(new BigDecimal("50"))
                        .lotNumber("LOT-202610")
                        .manufacturingDate(LocalDate.of(2027, 5, 1))
                        .expiryDate(LocalDate.of(2026, 12, 1)) // HSD trước NSX
                        .build()))
                .build();

        assertThatThrownBy(() -> warehouseService.importGoods(req, "staff"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hạn sử dụng không được trước ngày sản xuất");
    }

    @Test
    @DisplayName("Sản phẩm KHÔNG quản lý lô (manageLot=false) không cần số lô và HSD -> Nhập thành công")
    void nonLotProduct_WithoutLotInfo_Success() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(20L)).thenReturn(Optional.of(snackKhongManageLot));
        when(unitConversionService.getConversionFactor(snackKhongManageLot, "gói")).thenReturn(BigDecimal.ONE);
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 20L)).thenReturn(Optional.empty());
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> i.getArgument(0));

        WarehouseVoucherRequest req = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(20L)
                        .quantity(new BigDecimal("100"))
                        .build()))
                .build();

        WarehouseVoucherResponse resp = warehouseService.importGoods(req, "staff");
        assertThat(resp).isNotNull();
        assertThat(resp.getItems().get(0).getBaseQuantity()).isEqualByComparingTo("100");
        verify(inventoryRepository).save(any());
        verifyNoInteractions(lotInventoryRepository);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // YÊU CẦU 4: XÁC NHẬN PHIẾU MỚI CỘNG TỒN; PHIẾU NHÁP KHÔNG ẢNH HƯỞNG TỒN
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Lập phiếu nháp (DRAFT): lưu phiếu nhưng KHÔNG cộng tồn kho")
    void createDraftVoucher_DoesNotChangeInventory() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(20L)).thenReturn(Optional.of(snackKhongManageLot));
        when(unitConversionService.getConversionFactor(snackKhongManageLot, "gói")).thenReturn(BigDecimal.ONE);
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> {
            WarehouseVoucher v = i.getArgument(0);
            v.setId(55L);
            return v;
        });

        WarehouseVoucherRequest req = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .status("DRAFT") // Lưu nháp
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(20L)
                        .quantity(new BigDecimal("50"))
                        .build()))
                .build();

        WarehouseVoucherResponse resp = warehouseService.importGoods(req, "staff");

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus()).isEqualTo("DRAFT");
        // Không hề gọi cộng tồn hay lưu vào inventoryRepository
        verify(inventoryRepository, never()).save(any());
        verify(lotInventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Xác nhận phiếu nháp (confirmVoucher): lúc này mới thực hiện cộng tồn kho")
    void confirmDraftVoucher_AddsInventory() {
        WarehouseVoucherItem draftItem = WarehouseVoucherItem.builder()
                .product(biaKenManageLot)
                .unit("thùng")
                .quantity(new BigDecimal("10"))
                .conversionFactor(new BigDecimal("24"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("240"))
                .lotNumber("LOT-202610")
                .expiryDate(LocalDate.of(2027, 10, 15))
                .build();

        WarehouseVoucher draftVoucher = WarehouseVoucher.builder()
                .id(77L)
                .voucherCode("PNK-DRAFT-77")
                .type(WarehouseVoucherType.IMPORT)
                .warehouse(khoHcm)
                .status("DRAFT")
                .items(new ArrayList<>(List.of(draftItem)))
                .build();
        draftItem.setVoucher(draftVoucher);

        when(voucherRepository.findById(77L)).thenReturn(Optional.of(draftVoucher));
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(Optional.empty());
        when(lotInventoryRepository.findByWarehouseIdAndProductIdAndLotNumber(1L, 10L, "LOT-202610")).thenReturn(Optional.empty());
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> i.getArgument(0));

        WarehouseVoucherResponse resp = warehouseService.confirmVoucher(77L, "staff_xacnhan");

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus()).isEqualTo("CONFIRMED");
        // Kiểm tra đã cộng tồn kho
        verify(inventoryRepository).save(argThat(inv -> inv.getBaseQuantity().compareTo(new BigDecimal("240")) == 0));
        // Kiểm tra đã cộng tồn kho lô
        verify(lotInventoryRepository).save(argThat(lot -> lot.getBaseQuantity().compareTo(new BigDecimal("240")) == 0));
    }

    @Test
    @DisplayName("Thử xác nhận lại phiếu đã RECORDED/CONFIRMED -> Báo lỗi IllegalStateException")
    void confirmAlreadyConfirmedVoucher_ThrowsException() {
        WarehouseVoucher confirmedVoucher = WarehouseVoucher.builder()
                .id(88L)
                .voucherCode("PNK-88")
                .status("CONFIRMED")
                .build();
        when(voucherRepository.findById(88L)).thenReturn(Optional.of(confirmedVoucher));

        assertThatThrownBy(() -> warehouseService.confirmVoucher(88L, "staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chỉ có thể xác nhận phiếu ở trạng thái Nháp");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // YÊU CẦU 5: PHIẾU ĐÃ XÁC NHẬN KHÔNG SỬA ĐƯỢC, CHỈ LẬP PHIẾU ĐIỀU CHỈNH
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Phiếu đã xác nhận không được phép sửa (updateVoucher) -> Ném IllegalStateException")
    void updateConfirmedVoucher_ThrowsException() {
        WarehouseVoucher confirmedVoucher = WarehouseVoucher.builder()
                .id(99L)
                .voucherCode("PNK-99")
                .status("CONFIRMED")
                .build();
        when(voucherRepository.findById(99L)).thenReturn(Optional.of(confirmedVoucher));

        WarehouseVoucherRequest updateReq = WarehouseVoucherRequest.builder()
                .note("Ghi chú sửa đổi")
                .build();

        assertThatThrownBy(() -> warehouseService.updateVoucher(99L, updateReq, "staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh");
    }

    @Test
    @DisplayName("Phiếu đã xác nhận không được phép xóa (deleteVoucher) -> Ném IllegalStateException")
    void deleteConfirmedVoucher_ThrowsException() {
        WarehouseVoucher confirmedVoucher = WarehouseVoucher.builder()
                .id(99L)
                .voucherCode("PNK-99")
                .status("RECORDED")
                .build();
        when(voucherRepository.findById(99L)).thenReturn(Optional.of(confirmedVoucher));

        assertThatThrownBy(() -> warehouseService.deleteVoucher(99L, "staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Phiếu đã xác nhận không thể xóa, chỉ lập phiếu điều chỉnh");
    }

    @Test
    @DisplayName("Phiếu nháp (DRAFT) được phép sửa thông tin và mặt hàng")
    void updateDraftVoucher_Success() {
        WarehouseVoucher draftVoucher = WarehouseVoucher.builder()
                .id(66L)
                .voucherCode("PNK-DRAFT-66")
                .type(WarehouseVoucherType.IMPORT)
                .warehouse(khoHcm)
                .status("DRAFT")
                .items(new ArrayList<>())
                .build();

        when(voucherRepository.findById(66L)).thenReturn(Optional.of(draftVoucher));
        when(productRepository.findById(20L)).thenReturn(Optional.of(snackKhongManageLot));
        when(unitConversionService.getConversionFactor(snackKhongManageLot, "gói")).thenReturn(BigDecimal.ONE);
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> i.getArgument(0));

        WarehouseVoucherRequest updateReq = WarehouseVoucherRequest.builder()
                .documentNumber("SO-CT-MOI-123")
                .note("Ghi chú mới")
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(20L)
                        .quantity(new BigDecimal("80"))
                        .build()))
                .build();

        WarehouseVoucherResponse resp = warehouseService.updateVoucher(66L, updateReq, "staff");

        assertThat(resp).isNotNull();
        assertThat(resp.getDocumentNumber()).isEqualTo("SO-CT-MOI-123");
        assertThat(resp.getNote()).isEqualTo("Ghi chú mới");
        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getQuantity()).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("Lập phiếu điều chỉnh (adjustVoucher) cho phiếu đã xác nhận: liên kết phiếu gốc và cập nhật tồn")
    void adjustConfirmedVoucher_Success() {
        WarehouseVoucher originalVoucher = WarehouseVoucher.builder()
                .id(33L)
                .voucherCode("PNK-ORIGINAL-33")
                .type(WarehouseVoucherType.IMPORT)
                .warehouse(khoHcm)
                .supplier(nhaCungCapBia)
                .status("CONFIRMED")
                .build();

        when(voucherRepository.findById(33L)).thenReturn(Optional.of(originalVoucher));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaKenManageLot));
        when(unitConversionService.getConversionFactor(biaKenManageLot, "lon")).thenReturn(BigDecimal.ONE);
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(Optional.empty());
        when(lotInventoryRepository.findByWarehouseIdAndProductIdAndLotNumber(1L, 10L, "LOT-KEN-202610")).thenReturn(Optional.empty());
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(i -> {
            WarehouseVoucher v = i.getArgument(0);
            if (v.getId() == null) v.setId(44L);
            return v;
        });

        WarehouseVoucherRequest adjustReq = WarehouseVoucherRequest.builder()
                .adjustmentReason("Xe dỡ hàng bị vỡ 2 lon, điều chỉnh giảm tồn 2 lon")
                .delta(new BigDecimal("-2"))
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(new BigDecimal("2"))
                        .lotNumber("LOT-KEN-202610")
                        .build()))
                .build();

        WarehouseVoucherResponse adjustmentResp = warehouseService.adjustVoucher(33L, adjustReq, "manager_kho");

        assertThat(adjustmentResp).isNotNull();
        assertThat(adjustmentResp.getType()).isEqualTo(WarehouseVoucherType.ADJUSTMENT);
        assertThat(adjustmentResp.getReferenceVoucherId()).isEqualTo(33L);
        assertThat(adjustmentResp.getReferenceVoucherCode()).isEqualTo("PNK-ORIGINAL-33");
        assertThat(adjustmentResp.getAdjustmentReason()).isEqualTo("Xe dỡ hàng bị vỡ 2 lon, điều chỉnh giảm tồn 2 lon");
        assertThat(originalVoucher.getStatus()).isEqualTo("ADJUSTED");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // XEM TỒN KHO THEO LÔ
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tra cứu tồn kho theo lô: trả về đúng danh sách số lô, HSD và số lượng cơ sở")
    void getLotInventory_ReturnsLotStockList() {
        WarehouseLotInventory lot1 = WarehouseLotInventory.builder()
                .id(1L)
                .warehouse(khoHcm)
                .product(biaKenManageLot)
                .lotNumber("LOT-KEN-A")
                .expiryDate(LocalDate.of(2027, 5, 1))
                .baseQuantity(new BigDecimal("240"))
                .baseUnit("lon")
                .build();

        WarehouseLotInventory lot2 = WarehouseLotInventory.builder()
                .id(2L)
                .warehouse(khoHcm)
                .product(biaKenManageLot)
                .lotNumber("LOT-KEN-B")
                .expiryDate(LocalDate.of(2027, 8, 1))
                .baseQuantity(new BigDecimal("120"))
                .baseUnit("lon")
                .build();

        when(lotInventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(List.of(lot1, lot2));

        List<WarehouseLotInventoryResponse> result = warehouseService.getLotInventory(1L, 10L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getLotNumber()).isEqualTo("LOT-KEN-A");
        assertThat(result.get(0).getBaseQuantity()).isEqualByComparingTo("240");
        assertThat(result.get(1).getLotNumber()).isEqualTo("LOT-KEN-B");
        assertThat(result.get(1).getBaseQuantity()).isEqualByComparingTo("120");
    }
}

