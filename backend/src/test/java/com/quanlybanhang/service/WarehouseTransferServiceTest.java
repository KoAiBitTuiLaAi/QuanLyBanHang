package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.WarehouseTransferItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferReceiveItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferReceiveRequest;
import com.quanlybanhang.dto.request.WarehouseTransferRequest;
import com.quanlybanhang.dto.response.WarehouseTransferItemResponse;
import com.quanlybanhang.dto.response.WarehouseTransferResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.ProductRepository;
import com.quanlybanhang.repository.WarehouseInventoryRepository;
import com.quanlybanhang.repository.WarehouseRepository;
import com.quanlybanhang.repository.WarehouseTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WarehouseTransferService - Chuyển kho nội bộ (S5-07)")
class WarehouseTransferServiceTest {

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UnitConversionService unitConversionService;

    @Mock
    private WarehouseInventoryRepository inventoryRepository;

    @Mock
    private WarehouseTransferRepository warehouseTransferRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private WarehouseServiceImpl warehouseService;

    private Warehouse khoTong;
    private Warehouse khoChiNhanh;
    private Product biaHeineken;
    private WarehouseInventory tonKhoTong;
    private WarehouseInventory tonKhoChiNhanh;

    @BeforeEach
    void setUp() {
        khoTong = Warehouse.builder().id(1L).code("KHO-TONG").name("Kho Tổng").active(true).build();
        khoChiNhanh = Warehouse.builder().id(2L).code("KHO-CN").name("Kho Chi Nhánh").active(true).build();

        biaHeineken = Product.builder()
                .id(10L)
                .code("SP-KEN330")
                .name("Bia Heineken Sleek lon 330ml")
                .unit("lon")
                .price(new BigDecimal("19000"))
                .build();

        // Kho tổng ban đầu có 500 lon
        tonKhoTong = WarehouseInventory.builder()
                .id(101L)
                .warehouse(khoTong)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("500.0000"))
                .inTransitQuantity(BigDecimal.ZERO)
                .baseUnit("lon")
                .build();

        // Kho chi nhánh ban đầu có 50 lon, 0 lon đang trên đường
        tonKhoChiNhanh = WarehouseInventory.builder()
                .id(102L)
                .warehouse(khoChiNhanh)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("50.0000"))
                .inTransitQuantity(BigDecimal.ZERO)
                .baseUnit("lon")
                .build();
    }

    @Test
    @DisplayName("Tạo phiếu chuyển kho thành công: trừ tồn kho xuất, ghi nhận đang trên đường, kho đến chưa cộng")
    void createTransfer_Success_DeductsFromSource_RecordsInTransit_NotAddedToDestination() {
        WarehouseTransferRequest request = WarehouseTransferRequest.builder()
                .fromWarehouseId(1L)
                .toWarehouseId(2L)
                .note("Điều hàng từ Kho Tổng sang Chi Nhánh")
                .items(List.of(WarehouseTransferItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("10")) // 10 thùng = 240 lon
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoTong));
        when(warehouseRepository.findById(2L)).thenReturn(Optional.of(khoChiNhanh));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(Optional.of(tonKhoTong));
        when(inventoryRepository.findByWarehouseIdAndProductId(2L, 10L)).thenReturn(Optional.of(tonKhoChiNhanh));
        when(warehouseTransferRepository.save(any(WarehouseTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        WarehouseTransferResponse response = warehouseService.createTransfer(request, "thukho01");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(WarehouseTransferStatus.IN_TRANSIT);
        assertThat(response.getStatusDescription()).isEqualTo("Đang trên đường");
        assertThat(response.getFromWarehouseId()).isEqualTo(1L);
        assertThat(response.getToWarehouseId()).isEqualTo(2L);
        assertThat(response.getItems()).hasSize(1);

        WarehouseTransferItemResponse item = response.getItems().get(0);
        assertThat(item.getUnit()).isEqualTo("thùng");
        assertThat(item.getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(item.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("240.0000"));

        // Kiểm tra tồn kho kho xuất: 500 - 240 = 260 lon
        assertThat(tonKhoTong.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("260.0000"));

        // Kiểm tra tồn kho kho nhập: baseQuantity CHƯA ĐƯỢC CỘNG (vẫn là 50 lon)
        assertThat(tonKhoChiNhanh.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("50.0000"));

        // Hàng đang chuyển được ghi nhận là ĐANG TRÊN ĐƯỜNG tại kho đến (240 lon)
        assertThat(tonKhoChiNhanh.getInTransitQuantity()).isEqualByComparingTo(new BigDecimal("240.0000"));

        verify(inventoryRepository).save(tonKhoTong);
        verify(inventoryRepository).save(tonKhoChiNhanh);
        verify(warehouseTransferRepository).save(any(WarehouseTransfer.class));
    }

    @Test
    @DisplayName("Kho xuất và kho nhập trùng nhau -> ném ngoại lệ chặn chuyển")
    void createTransfer_SameWarehouse_ThrowsException() {
        WarehouseTransferRequest request = WarehouseTransferRequest.builder()
                .fromWarehouseId(1L)
                .toWarehouseId(1L)
                .items(List.of(WarehouseTransferItemRequest.builder()
                        .productId(10L)
                        .quantity(new BigDecimal("5"))
                        .build()))
                .build();

        assertThatThrownBy(() -> warehouseService.createTransfer(request, "thukho01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kho xuất và kho nhập không được trùng nhau");

        verifyNoInteractions(warehouseTransferRepository);
    }

    @Test
    @DisplayName("Kho xuất không đủ tồn kho cơ sở -> ném ngoại lệ chặn xuất âm")
    void createTransfer_InsufficientStock_ThrowsException() {
        // Tồn kho tổng chỉ có 500 lon, yêu cầu chuyển 30 thùng = 720 lon
        WarehouseTransferRequest request = WarehouseTransferRequest.builder()
                .fromWarehouseId(1L)
                .toWarehouseId(2L)
                .items(List.of(WarehouseTransferItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("30"))
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoTong));
        when(warehouseRepository.findById(2L)).thenReturn(Optional.of(khoChiNhanh));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(Optional.of(tonKhoTong));

        assertThatThrownBy(() -> warehouseService.createTransfer(request, "thukho01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không đủ tồn kho để chuyển");

        // Không sửa tồn kho
        assertThat(tonKhoTong.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("500.0000"));
        verify(warehouseTransferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Kho đến xác nhận nhận ĐỦ: tồn mới được cộng, hàng trên đường được xóa về 0")
    void receiveTransfer_FullQuantity_AddsStockToDestination_ClearsInTransit() {
        // Đặt kho chi nhánh đang có hàng trên đường 240 lon
        tonKhoChiNhanh.setInTransitQuantity(new BigDecimal("240.0000"));

        WarehouseTransfer transfer = WarehouseTransfer.builder()
                .id(88L)
                .transferCode("PCK-0088")
                .fromWarehouse(khoTong)
                .toWarehouse(khoChiNhanh)
                .status(WarehouseTransferStatus.IN_TRANSIT)
                .createdAt(LocalDateTime.now())
                .items(new ArrayList<>())
                .build();

        WarehouseTransferItem transferItem = WarehouseTransferItem.builder()
                .id(501L)
                .transfer(transfer)
                .product(biaHeineken)
                .unit("thùng")
                .quantity(new BigDecimal("10"))
                .conversionFactor(new BigDecimal("24.0000"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("240.0000"))
                .build();
        transfer.addItem(transferItem);

        when(warehouseTransferRepository.findById(88L)).thenReturn(Optional.of(transfer));
        when(inventoryRepository.findByWarehouseIdAndProductId(2L, 10L)).thenReturn(Optional.of(tonKhoChiNhanh));
        when(warehouseTransferRepository.save(any(WarehouseTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        // Xác nhận nhận đủ (không truyền chi tiết hoặc nhận đủ 10 thùng)
        WarehouseTransferReceiveRequest receiveRequest = WarehouseTransferReceiveRequest.builder()
                .note("Đã nhận đủ hàng tại kho Chi Nhánh")
                .build();

        WarehouseTransferResponse response = warehouseService.receiveTransfer(88L, receiveRequest, "nhanvien_cn");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(WarehouseTransferStatus.COMPLETED);
        assertThat(response.getStatusDescription()).isEqualTo("Đã nhận hàng");
        assertThat(response.getReceivedBy()).isEqualTo("nhanvien_cn");

        // Tồn kho tại kho đến BÂY GIỜ MỚI ĐƯỢC CỘNG: 50 + 240 = 290 lon
        assertThat(tonKhoChiNhanh.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("290.0000"));

        // Hàng trên đường đã trừ hết (240 - 240 = 0)
        assertThat(tonKhoChiNhanh.getInTransitQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(inventoryRepository).save(tonKhoChiNhanh);
    }

    @Test
    @DisplayName("Chênh lệch khi nhận hàng KHÔNG nhập lý do -> ném ngoại lệ từ chối")
    void receiveTransfer_DiscrepancyWithoutReason_ThrowsException() {
        tonKhoChiNhanh.setInTransitQuantity(new BigDecimal("240.0000"));

        WarehouseTransfer transfer = WarehouseTransfer.builder()
                .id(88L)
                .fromWarehouse(khoTong)
                .toWarehouse(khoChiNhanh)
                .status(WarehouseTransferStatus.IN_TRANSIT)
                .items(new ArrayList<>())
                .build();

        WarehouseTransferItem transferItem = WarehouseTransferItem.builder()
                .id(501L)
                .transfer(transfer)
                .product(biaHeineken)
                .unit("thùng")
                .quantity(new BigDecimal("10"))
                .conversionFactor(new BigDecimal("24.0000"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("240.0000"))
                .build();
        transfer.addItem(transferItem);

        when(warehouseTransferRepository.findById(88L)).thenReturn(Optional.of(transfer));

        // Nhận thiếu: gửi 10 thùng nhưng nhận 9 thùng, lý do để trống!
        WarehouseTransferReceiveRequest receiveRequest = WarehouseTransferReceiveRequest.builder()
                .items(List.of(WarehouseTransferReceiveItemRequest.builder()
                        .itemId(501L)
                        .receivedQuantity(new BigDecimal("9"))
                        .discrepancyReason("") // Blank!
                        .build()))
                .build();

        assertThatThrownBy(() -> warehouseService.receiveTransfer(88L, receiveRequest, "nhanvien_cn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bắt buộc phải nhập lý do");

        // Không cộng tồn và không lưu
        assertThat(tonKhoChiNhanh.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("50.0000"));
        verify(warehouseTransferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Chênh lệch khi nhận hàng CÓ nhập lý do: tồn kho đến chỉ cộng số lượng thực nhận")
    void receiveTransfer_DiscrepancyWithReason_AddsOnlyActualReceivedStock() {
        tonKhoChiNhanh.setInTransitQuantity(new BigDecimal("240.0000"));

        WarehouseTransfer transfer = WarehouseTransfer.builder()
                .id(88L)
                .fromWarehouse(khoTong)
                .toWarehouse(khoChiNhanh)
                .status(WarehouseTransferStatus.IN_TRANSIT)
                .items(new ArrayList<>())
                .build();

        WarehouseTransferItem transferItem = WarehouseTransferItem.builder()
                .id(501L)
                .transfer(transfer)
                .product(biaHeineken)
                .unit("thùng")
                .quantity(new BigDecimal("10")) // 10 thùng = 240 lon
                .conversionFactor(new BigDecimal("24.0000"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("240.0000"))
                .build();
        transfer.addItem(transferItem);

        when(warehouseTransferRepository.findById(88L)).thenReturn(Optional.of(transfer));
        when(inventoryRepository.findByWarehouseIdAndProductId(2L, 10L)).thenReturn(Optional.of(tonKhoChiNhanh));
        when(warehouseTransferRepository.save(any(WarehouseTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        // Nhận 9 thùng (216 lon), thiếu 1 thùng (24 lon) do va đập bể vỡ
        WarehouseTransferReceiveRequest receiveRequest = WarehouseTransferReceiveRequest.builder()
                .items(List.of(WarehouseTransferReceiveItemRequest.builder()
                        .itemId(501L)
                        .receivedQuantity(new BigDecimal("9"))
                        .discrepancyReason("Bể vỡ khi vận chuyển 1 thùng trên xe")
                        .build()))
                .build();

        WarehouseTransferResponse response = warehouseService.receiveTransfer(88L, receiveRequest, "nhanvien_cn");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(WarehouseTransferStatus.COMPLETED);

        WarehouseTransferItemResponse itemResp = response.getItems().get(0);
        assertThat(itemResp.getReceivedQuantity()).isEqualByComparingTo(new BigDecimal("9"));
        assertThat(itemResp.getReceivedBaseQuantity()).isEqualByComparingTo(new BigDecimal("216.0000"));
        assertThat(itemResp.getDiscrepancyQuantity()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(itemResp.getDiscrepancyBaseQuantity()).isEqualByComparingTo(new BigDecimal("24.0000"));
        assertThat(itemResp.getDiscrepancyReason()).isEqualTo("Bể vỡ khi vận chuyển 1 thùng trên xe");

        // Tồn kho đến CHỈ CỘNG số lượng thực nhận (216 lon): 50 + 216 = 266 lon
        assertThat(tonKhoChiNhanh.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("266.0000"));

        // Hàng trên đường bị xóa hết 240 lon
        assertThat(tonKhoChiNhanh.getInTransitQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Hủy phiếu chuyển khi đang trên đường -> hoàn tồn kho xuất, xóa hàng trên đường")
    void cancelTransfer_InTransit_RestoresSourceStock_ClearsInTransit() {
        tonKhoTong.setBaseQuantity(new BigDecimal("260.0000"));
        tonKhoChiNhanh.setInTransitQuantity(new BigDecimal("240.0000"));

        WarehouseTransfer transfer = WarehouseTransfer.builder()
                .id(88L)
                .fromWarehouse(khoTong)
                .toWarehouse(khoChiNhanh)
                .status(WarehouseTransferStatus.IN_TRANSIT)
                .items(new ArrayList<>())
                .build();

        WarehouseTransferItem transferItem = WarehouseTransferItem.builder()
                .id(501L)
                .transfer(transfer)
                .product(biaHeineken)
                .unit("thùng")
                .quantity(new BigDecimal("10"))
                .conversionFactor(new BigDecimal("24.0000"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("240.0000"))
                .build();
        transfer.addItem(transferItem);

        when(warehouseTransferRepository.findById(88L)).thenReturn(Optional.of(transfer));
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(Optional.of(tonKhoTong));
        when(inventoryRepository.findByWarehouseIdAndProductId(2L, 10L)).thenReturn(Optional.of(tonKhoChiNhanh));
        when(warehouseTransferRepository.save(any(WarehouseTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        WarehouseTransferResponse response = warehouseService.cancelTransfer(88L, "Hủy do thay đổi kế hoạch điều vận", "quanlykho");

        assertThat(response.getStatus()).isEqualTo(WarehouseTransferStatus.CANCELLED);
        assertThat(response.getCancelReason()).isEqualTo("Hủy do thay đổi kế hoạch điều vận");

        // Kho xuất được hoàn lại tồn: 260 + 240 = 500 lon
        assertThat(tonKhoTong.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("500.0000"));

        // Hàng trên đường ở kho nhập bị xóa: 240 - 240 = 0
        assertThat(tonKhoChiNhanh.getInTransitQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
