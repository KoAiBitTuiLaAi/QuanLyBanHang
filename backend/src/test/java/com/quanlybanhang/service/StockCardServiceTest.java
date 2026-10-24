package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.StockCardEntryResponse;
import com.quanlybanhang.dto.response.StockCardResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockCardServiceTest {

    @Mock private WarehouseVoucherItemRepository voucherItemRepo;
    @Mock private WarehouseTransferItemRepository transferItemRepo;
    @Mock private ProductRepository productRepo;
    @Mock private StockLedgerService ledgerService;
    @Mock private WarehouseInventoryRepository inventoryRepo;
    @Mock private WarehouseStockCountItemRepository countItemRepo;
    @Mock private OrderItemRepository orderItemRepo;
    @Mock private OrderStatusHistoryRepository historyRepo;
    @Mock private DeliveryTripStopItemRepository returnItemRepo;

    @InjectMocks
    private StockCardService service;

    private Warehouse wh1;
    private Warehouse wh2;
    private Product product;

    @BeforeEach
    void setUp() {
        wh1 = Warehouse.builder().id(1L).code("WH01").name("Kho Hà Nội").build();
        wh2 = Warehouse.builder().id(2L).code("WH02").name("Kho Sài Gòn").build();
        product = Product.builder().id(100L).code("SP01").name("Sữa TH 1L").unit("Hộp").build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnedDeliveryStockAppearsAtActualReturnTimeWithOriginalStopIdentity() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of(wh1));
        when(productRepo.findById(100L)).thenReturn(Optional.of(product));
        var t=LocalDateTime.of(2026,10,22,14,0);
        var trip=DeliveryTrip.builder().id(8L).tripCode("CG-008").warehouse(wh1).build();
        var stop=DeliveryTripStop.builder().id(9L).trip(trip).returnedToWarehouse(true).returnedAt(t).note("Nhập trả hàng chưa giao").build();
        var item=DeliveryTripStopItem.builder().id(10L).stop(stop).product(product).quantity(new BigDecimal("6")).unit("Hộp").build();
        when(returnItemRepo.findForStockCard(100L,List.of(1L))).thenReturn(List.of(item));
        when(inventoryRepo.findByProductId(100L)).thenReturn(List.of(WarehouseInventory.builder().warehouse(wh1).product(product).baseQuantity(new BigDecimal("106")).build()));
        var card=service.getStockCard(100L,1L,null,null,0,50);
        assertThat(card.openingBalance()).isEqualByComparingTo("100");
        assertThat(card.entries()).hasSize(1);
        assertThat(card.entries().get(0).sourceType()).isEqualTo("DELIVERY_RETURN");
        assertThat(card.entries().get(0).sourceId()).isEqualTo(9L);
        assertThat(card.entries().get(0).runningBaseBalance()).isEqualByComparingTo("106");
        assertThat(card.entries().get(0).eventTime()).isEqualTo(t);
    }

    @Test
    void invalidParametersThrowIllegalArgumentException() {
        assertThatThrownBy(() -> service.getStockCard(null, null, null, null, 0, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productId không hợp lệ");

        assertThatThrownBy(() -> service.getStockCard(0L, null, null, null, 0, 50))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.getStockCard(100L, -1L, null, null, 0, 50))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.getStockCard(100L, null, null, null, -1, 50))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.getStockCard(100L, null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.getStockCard(100L, null, null, null, 0, 501))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(voucherItemRepo, transferItemRepo, productRepo);
    }

    @Test
    void accessDeniedWhenLedgerServiceThrowsAccessDenied() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW"))
                .thenThrow(new AccessDeniedException("Forbidden"));

        assertThatThrownBy(() -> service.getStockCard(100L, null, null, null, 0, 50))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(voucherItemRepo, transferItemRepo, productRepo);
    }

    @Test
    void accessDeniedWhenWarehouseIdOutsideUserScope() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of(wh1));

        assertThatThrownBy(() -> service.getStockCard(100L, 2L, null, null, 0, 50))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bạn không có quyền xem thẻ kho của kho này");

        verifyNoInteractions(voucherItemRepo, transferItemRepo, productRepo);
    }

    @Test
    void emptyScopeReturnsEmptyCard() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of());
        when(productRepo.findById(100L)).thenReturn(Optional.of(product));

        StockCardResponse response = service.getStockCard(100L, null, null, null, 0, 50);

        assertThat(response.productId()).isEqualTo(100L);
        assertThat(response.entries()).isEmpty();
        assertThat(response.totalElements()).isEqualTo(0);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.openingBalance()).isNull();
        verifyNoInteractions(voucherItemRepo, transferItemRepo);
    }

    @Test
    void productNotFoundThrowsIllegalArgumentException() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of(wh1));
        when(productRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStockCard(999L, null, null, null, 0, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không tìm thấy sản phẩm");

        verifyNoInteractions(voucherItemRepo, transferItemRepo);
    }

    @Test
    void chronologicalSortingAndRunningBalanceForVouchers() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of(wh1));
        when(productRepo.findById(100L)).thenReturn(Optional.of(product));

        LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 1, 2, 11, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 1, 3, 12, 0);

        // Voucher 1: IMPORT 100
        WarehouseVoucher v1 = WarehouseVoucher.builder()
                .id(1L).voucherCode("PN001").type(WarehouseVoucherType.IMPORT)
                .status("RECORDED").recordedAt(t1).warehouse(wh1).build();
        WarehouseVoucherItem item1 = WarehouseVoucherItem.builder()
                .id(10L).voucher(v1).product(product).baseQuantity(new BigDecimal("100"))
                .baseUnit("Hộp").quantity(new BigDecimal("100")).unit("Hộp").build();

        // Voucher 2: EXPORT 30
        WarehouseVoucher v2 = WarehouseVoucher.builder()
                .id(2L).voucherCode("PX001").type(WarehouseVoucherType.EXPORT)
                .status("RECORDED").recordedAt(t2).warehouse(wh1).build();
        WarehouseVoucherItem item2 = WarehouseVoucherItem.builder()
                .id(20L).voucher(v2).product(product).baseQuantity(new BigDecimal("30"))
                .baseUnit("Hộp").quantity(new BigDecimal("30")).unit("Hộp").build();

        // Voucher 3: ADJUSTMENT 10
        WarehouseVoucher v3 = WarehouseVoucher.builder()
                .id(3L).voucherCode("PKK001").type(WarehouseVoucherType.ADJUSTMENT)
                .status("RECORDED").recordedAt(t3).warehouse(wh1).build();
        WarehouseVoucherItem item3 = WarehouseVoucherItem.builder()
                .id(30L).voucher(v3).product(product).baseQuantity(new BigDecimal("10"))
                .baseUnit("Hộp").quantity(new BigDecimal("10")).unit("Hộp").build();

        // Deliberately return out of order to verify sorting in service
        when(voucherItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull()))
                .thenReturn(List.of(item2, item3, item1));
        when(transferItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull()))
                .thenReturn(List.of());

        StockCardResponse response = service.getStockCard(100L, 1L, null, null, 0, 50);

        assertThat(response.productId()).isEqualTo(100L);
        assertThat(response.productCode()).isEqualTo("SP01");
        assertThat(response.productName()).isEqualTo("Sữa TH 1L");
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.currentPage()).isEqualTo(0);
        assertThat(response.openingBalance()).isNull(); // page 0

        List<StockCardEntryResponse> entries = response.entries();
        assertThat(entries).hasSize(3);

        // Entry 1: IMPORT +100 -> balance 100
        StockCardEntryResponse e1 = entries.get(0);
        assertThat(e1.rowNumber()).isEqualTo(1);
        assertThat(e1.eventTime()).isEqualTo(t1);
        assertThat(e1.eventType()).isEqualTo("IMPORT");
        assertThat(e1.baseQuantityChange()).isEqualByComparingTo("100");
        assertThat(e1.runningBaseBalance()).isEqualByComparingTo("100");
        assertThat(e1.sourceType()).isEqualTo("VOUCHER");
        assertThat(e1.sourceCode()).isEqualTo("PN001");

        // Entry 2: EXPORT -30 -> balance 70
        StockCardEntryResponse e2 = entries.get(1);
        assertThat(e2.rowNumber()).isEqualTo(2);
        assertThat(e2.eventTime()).isEqualTo(t2);
        assertThat(e2.eventType()).isEqualTo("EXPORT");
        assertThat(e2.baseQuantityChange()).isEqualByComparingTo("-30");
        assertThat(e2.runningBaseBalance()).isEqualByComparingTo("70");
        assertThat(e2.sourceCode()).isEqualTo("PX001");

        // Entry 3: ADJUSTMENT +10 -> balance 80
        StockCardEntryResponse e3 = entries.get(2);
        assertThat(e3.rowNumber()).isEqualTo(3);
        assertThat(e3.eventTime()).isEqualTo(t3);
        assertThat(e3.eventType()).isEqualTo("ADJUSTMENT");
        assertThat(e3.baseQuantityChange()).isEqualByComparingTo("10");
        assertThat(e3.runningBaseBalance()).isEqualByComparingTo("80");
        assertThat(e3.sourceCode()).isEqualTo("PKK001");
    }

    @Test
    void transferDispatchAndReceiveEventsWhenCompleted() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of(wh1, wh2));
        when(productRepo.findById(100L)).thenReturn(Optional.of(product));

        LocalDateTime dispatchTime = LocalDateTime.of(2026, 2, 1, 9, 0);
        LocalDateTime receiveTime = LocalDateTime.of(2026, 2, 2, 14, 0);

        WarehouseTransfer transfer = WarehouseTransfer.builder()
                .id(50L).transferCode("CK001").fromWarehouse(wh1).toWarehouse(wh2)
                .status(WarehouseTransferStatus.COMPLETED)
                .dispatchedAt(dispatchTime).receivedAt(receiveTime).build();

        WarehouseTransferItem item = WarehouseTransferItem.builder()
                .id(501L).transfer(transfer).product(product)
                .baseQuantity(new BigDecimal("25")).baseUnit("Hộp")
                .quantity(new BigDecimal("25")).unit("Hộp")
                .receivedBaseQuantity(new BigDecimal("25")).receivedQuantity(new BigDecimal("25"))
                .build();

        when(voucherItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull())).thenReturn(List.of());
        when(transferItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull())).thenReturn(List.of(item));

        StockCardResponse response = service.getStockCard(100L, null, null, null, 0, 50);

        assertThat(response.entries()).hasSize(2);

        // Event 1: DISPATCH from WH01 (-25)
        StockCardEntryResponse e1 = response.entries().get(0);
        assertThat(e1.eventType()).isEqualTo("TRANSFER_DISPATCH");
        assertThat(e1.warehouseCode()).isEqualTo("WH01");
        assertThat(e1.baseQuantityChange()).isEqualByComparingTo("-25");
        assertThat(e1.runningBaseBalance()).isEqualByComparingTo("-25");
        assertThat(e1.sourceType()).isEqualTo("TRANSFER");
        assertThat(e1.sourceId()).isEqualTo(50L);

        // Event 2: RECEIVE at WH02 (+25)
        StockCardEntryResponse e2 = response.entries().get(1);
        assertThat(e2.eventType()).isEqualTo("TRANSFER_RECEIVE");
        assertThat(e2.warehouseCode()).isEqualTo("WH02");
        assertThat(e2.baseQuantityChange()).isEqualByComparingTo("25");
        assertThat(e2.runningBaseBalance()).isEqualByComparingTo("0");
    }

    @Test
    void inTransitTransferOnlyGeneratesDispatchEvent() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of(wh1, wh2));
        when(productRepo.findById(100L)).thenReturn(Optional.of(product));

        LocalDateTime dispatchTime = LocalDateTime.of(2026, 2, 1, 9, 0);

        WarehouseTransfer transfer = WarehouseTransfer.builder()
                .id(51L).transferCode("CK002").fromWarehouse(wh1).toWarehouse(wh2)
                .status(WarehouseTransferStatus.IN_TRANSIT)
                .dispatchedAt(dispatchTime).build();

        WarehouseTransferItem item = WarehouseTransferItem.builder()
                .id(502L).transfer(transfer).product(product)
                .baseQuantity(new BigDecimal("15")).baseUnit("Hộp")
                .quantity(new BigDecimal("15")).unit("Hộp")
                .build();

        when(voucherItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull())).thenReturn(List.of());
        when(transferItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull())).thenReturn(List.of(item));

        StockCardResponse response = service.getStockCard(100L, null, null, null, 0, 50);

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().get(0).eventType()).isEqualTo("TRANSFER_DISPATCH");
        assertThat(response.entries().get(0).baseQuantityChange()).isEqualByComparingTo("-15");
    }

    @Test
    void cancelledTransferGeneratesDispatchAndCancelRestoreEvents() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of(wh1));
        when(productRepo.findById(100L)).thenReturn(Optional.of(product));

        LocalDateTime dispatchTime = LocalDateTime.of(2026, 2, 1, 9, 0);
        LocalDateTime cancelTime = LocalDateTime.of(2026, 2, 1, 15, 0);

        WarehouseTransfer transfer = WarehouseTransfer.builder()
                .id(52L).transferCode("CK003").fromWarehouse(wh1).toWarehouse(wh2)
                .status(WarehouseTransferStatus.CANCELLED)
                .dispatchedAt(dispatchTime).cancelledAt(cancelTime)
                .cancelReason("Hàng bị hư hỏng trên đường")
                .build();

        WarehouseTransferItem item = WarehouseTransferItem.builder()
                .id(503L).transfer(transfer).product(product)
                .baseQuantity(new BigDecimal("20")).baseUnit("Hộp")
                .quantity(new BigDecimal("20")).unit("Hộp")
                .build();

        when(voucherItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull())).thenReturn(List.of());
        when(transferItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull())).thenReturn(List.of(item));

        StockCardResponse response = service.getStockCard(100L, 1L, null, null, 0, 50);

        assertThat(response.entries()).hasSize(2);

        StockCardEntryResponse e1 = response.entries().get(0);
        assertThat(e1.eventType()).isEqualTo("TRANSFER_DISPATCH");
        assertThat(e1.baseQuantityChange()).isEqualByComparingTo("-20");
        assertThat(e1.runningBaseBalance()).isEqualByComparingTo("-20");

        StockCardEntryResponse e2 = response.entries().get(1);
        assertThat(e2.eventType()).isEqualTo("TRANSFER_CANCEL");
        assertThat(e2.baseQuantityChange()).isEqualByComparingTo("20");
        assertThat(e2.runningBaseBalance()).isEqualByComparingTo("0");
        assertThat(e2.note()).isEqualTo("Hàng bị hư hỏng trên đường");
    }

    @Test
    void paginationAndOpeningBalanceCalculatedProperly() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of(wh1));
        when(productRepo.findById(100L)).thenReturn(Optional.of(product));

        LocalDateTime t1 = LocalDateTime.of(2026, 3, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 3, 2, 10, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 3, 3, 10, 0);

        WarehouseVoucher v1 = WarehouseVoucher.builder().id(1L).voucherCode("PN1").type(WarehouseVoucherType.IMPORT)
                .status("RECORDED").recordedAt(t1).warehouse(wh1).build();
        WarehouseVoucherItem i1 = WarehouseVoucherItem.builder().id(1L).voucher(v1).product(product)
                .baseQuantity(new BigDecimal("50")).baseUnit("Hộp").quantity(new BigDecimal("50")).unit("Hộp").build();

        WarehouseVoucher v2 = WarehouseVoucher.builder().id(2L).voucherCode("PX1").type(WarehouseVoucherType.EXPORT)
                .status("RECORDED").recordedAt(t2).warehouse(wh1).build();
        WarehouseVoucherItem i2 = WarehouseVoucherItem.builder().id(2L).voucher(v2).product(product)
                .baseQuantity(new BigDecimal("10")).baseUnit("Hộp").quantity(new BigDecimal("10")).unit("Hộp").build();

        WarehouseVoucher v3 = WarehouseVoucher.builder().id(3L).voucherCode("PX2").type(WarehouseVoucherType.EXPORT)
                .status("RECORDED").recordedAt(t3).warehouse(wh1).build();
        WarehouseVoucherItem i3 = WarehouseVoucherItem.builder().id(3L).voucher(v3).product(product)
                .baseQuantity(new BigDecimal("5")).baseUnit("Hộp").quantity(new BigDecimal("5")).unit("Hộp").build();

        when(voucherItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull()))
                .thenReturn(List.of(i1, i2, i3));
        when(transferItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull()))
                .thenReturn(List.of());

        // Test Page 0, Size 2
        StockCardResponse p0 = service.getStockCard(100L, 1L, null, null, 0, 2);
        assertThat(p0.totalElements()).isEqualTo(3);
        assertThat(p0.totalPages()).isEqualTo(2);
        assertThat(p0.currentPage()).isEqualTo(0);
        assertThat(p0.openingBalance()).isNull();
        assertThat(p0.entries()).hasSize(2);
        assertThat(p0.entries().get(0).rowNumber()).isEqualTo(1);
        assertThat(p0.entries().get(1).rowNumber()).isEqualTo(2);

        // Test Page 1, Size 2
        StockCardResponse p1 = service.getStockCard(100L, 1L, null, null, 1, 2);
        assertThat(p1.totalElements()).isEqualTo(3);
        assertThat(p1.totalPages()).isEqualTo(2);
        assertThat(p1.currentPage()).isEqualTo(1);
        // Opening balance for page 1 is running balance of entry 2 (+50 - 10 = +40)
        assertThat(p1.openingBalance()).isEqualByComparingTo("40");
        assertThat(p1.entries()).hasSize(1);
        assertThat(p1.entries().get(0).rowNumber()).isEqualTo(3);
        assertThat(p1.entries().get(0).runningBaseBalance()).isEqualByComparingTo("35");
    }

    @Test
    void dateRangeFilterAppliesAccurately() {
        when(ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW")).thenReturn(List.of(wh1));
        when(productRepo.findById(100L)).thenReturn(Optional.of(product));

        LocalDateTime tJan = LocalDateTime.of(2026, 1, 15, 10, 0);
        LocalDateTime tFeb = LocalDateTime.of(2026, 2, 15, 10, 0);
        LocalDateTime tMar = LocalDateTime.of(2026, 3, 15, 10, 0);

        WarehouseVoucher vJan = WarehouseVoucher.builder().id(1L).voucherCode("PN1").type(WarehouseVoucherType.IMPORT)
                .status("RECORDED").recordedAt(tJan).warehouse(wh1).build();
        WarehouseVoucherItem iJan = WarehouseVoucherItem.builder().id(1L).voucher(vJan).product(product)
                .baseQuantity(new BigDecimal("10")).baseUnit("Hộp").quantity(new BigDecimal("10")).unit("Hộp").build();

        WarehouseVoucher vFeb = WarehouseVoucher.builder().id(2L).voucherCode("PN2").type(WarehouseVoucherType.IMPORT)
                .status("RECORDED").recordedAt(tFeb).warehouse(wh1).build();
        WarehouseVoucherItem iFeb = WarehouseVoucherItem.builder().id(2L).voucher(vFeb).product(product)
                .baseQuantity(new BigDecimal("20")).baseUnit("Hộp").quantity(new BigDecimal("20")).unit("Hộp").build();

        WarehouseVoucher vMar = WarehouseVoucher.builder().id(3L).voucherCode("PN3").type(WarehouseVoucherType.IMPORT)
                .status("RECORDED").recordedAt(tMar).warehouse(wh1).build();
        WarehouseVoucherItem iMar = WarehouseVoucherItem.builder().id(3L).voucher(vMar).product(product)
                .baseQuantity(new BigDecimal("30")).baseUnit("Hộp").quantity(new BigDecimal("30")).unit("Hộp").build();

        when(voucherItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull()))
                .thenReturn(List.of(iJan, iFeb, iMar));
        when(transferItemRepo.findForStockCard(eq(100L), any(), isNull(), isNull()))
                .thenReturn(List.of());

        LocalDateTime from = LocalDateTime.of(2026, 2, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 2, 28, 23, 59);

        StockCardResponse response = service.getStockCard(100L, 1L, from, to, 0, 50);

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().get(0).sourceCode()).isEqualTo("PN2");
        assertThat(response.entries().get(0).baseQuantityChange()).isEqualByComparingTo("20");
        assertThat(response.openingBalance()).isEqualByComparingTo("10");
        assertThat(response.entries().get(0).runningBaseBalance()).isEqualByComparingTo("30");
    }
}
