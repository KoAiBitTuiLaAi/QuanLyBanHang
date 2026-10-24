package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.CreateDeliveryTripRequest;
import com.quanlybanhang.dto.request.CreateDeliveryTripStopItemRequest;
import com.quanlybanhang.dto.request.CreateDeliveryTripStopRequest;
import com.quanlybanhang.dto.request.ReturnStopGoodsRequest;
import com.quanlybanhang.dto.request.UpdateDeliveryStopStatusRequest;
import com.quanlybanhang.dto.response.DeliveryTripOptionsResponse;
import com.quanlybanhang.dto.response.DeliveryTripResponse;
import com.quanlybanhang.dto.response.DeliveryTripStopResponse;
import com.quanlybanhang.dto.response.DeliveryTripSummaryResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.impl.DeliveryTripServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryTripServiceTest - Kiểm thử nghiệp vụ Chuyến giao hàng & Tiền hàng trên đường (S6-07)")
class DeliveryTripServiceTest {

    @Mock
    private DeliveryTripRepository tripRepository;

    @Mock
    private DeliveryTripStopRepository stopRepository;

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private WarehouseInventoryRepository inventoryRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private DealerDeliveryPointRepository deliveryPointRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private DeliveryTripServiceImpl tripService;

    private Warehouse warehouse1;
    private Warehouse warehouse2;
    private User adminUser;
    private User staffUser;

    @BeforeEach
    void setUp() {
        warehouse1 = Warehouse.builder().id(1L).code("KHO-HN").name("Kho Hà Nội").active(true).build();
        warehouse2 = Warehouse.builder().id(2L).code("KHO-HCM").name("Kho TP.HCM").active(true).build();

        adminUser = User.builder().id(1L).username("admin").active(true).build();

        Set<Warehouse> assigned = new HashSet<>();
        assigned.add(warehouse1);
        staffUser = User.builder().id(2L).username("staff_hn").active(true).warehouses(assigned).build();

        // Mặc định đăng nhập với quyền ADMIN
        setAuth("admin", "ROLE_ADMIN", "WAREHOUSE_VIEW", "WAREHOUSE_EXPORT");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuth(String username, String... authorities) {
        var auths = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        var token = new UsernamePasswordAuthenticationToken(username, "pass", auths);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // ─────────────────────────────────────────────────────────────
    // 1. TÙY CHỌN & PHÂN QUYỀN
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin lấy options: xem được toàn bộ kho đang hoạt động")
    void getOptions_Admin_ReturnsAllActiveWarehouses() {
        when(warehouseRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(warehouse1, warehouse2));

        DeliveryTripOptionsResponse response = tripService.getOptions();

        assertThat(response).isNotNull();
        assertThat(response.getWarehouses()).hasSize(2);
        assertThat(response.isCanManage()).isTrue();
        assertThat(response.getTripStatuses()).isNotEmpty();
        assertThat(response.getStopStatuses()).isNotEmpty();
    }

    @Test
    @DisplayName("Nhân viên chỉ được giao Kho Hà Nội: Options chỉ trả Kho Hà Nội")
    void getOptions_ScopedUser_ReturnsOnlyAssignedWarehouses() {
        setAuth("staff_hn", "ROLE_WAREHOUSE_STAFF", "WAREHOUSE_VIEW");
        when(userRepository.findByUsername("staff_hn")).thenReturn(Optional.of(staffUser));
        when(warehouseRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(warehouse1, warehouse2));

        DeliveryTripOptionsResponse response = tripService.getOptions();

        assertThat(response.getWarehouses()).hasSize(1);
        assertThat(response.getWarehouses().get(0).code()).isEqualTo("KHO-HN");
        assertThat(response.isCanManage()).isFalse(); // Chỉ có VIEW, không có EXPORT
    }

    @Test
    @DisplayName("Không có quyền WAREHOUSE_VIEW hay WAREHOUSE_EXPORT: ném AccessDeniedException")
    void getOptions_NoPermission_ThrowsAccessDenied() {
        setAuth("guest", "ROLE_USER");

        assertThatThrownBy(() -> tripService.getOptions())
                .isInstanceOf(AccessDeniedException.class);
    }

    // ─────────────────────────────────────────────────────────────
    // 2. TẠO CHUYẾN GIAO HÀNG
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tạo chuyến giao thành công với điểm giao tùy chỉnh")
    void createTrip_WithCustomStops_Success() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse1));
        when(tripRepository.existsByTripCode(anyString())).thenReturn(false);
        when(tripRepository.save(any(DeliveryTrip.class))).thenAnswer(invocation -> {
            DeliveryTrip t = invocation.getArgument(0);
            t.setId(10L);
            long idGen = 100L;
            for (DeliveryTripStop s : t.getStops()) {
                s.setId(idGen++);
            }
            return t;
        });

        CreateDeliveryTripRequest req = CreateDeliveryTripRequest.builder()
                .warehouseId(1L)
                .driverName("Nguyễn Văn Tài")
                .driverPhone("0988123456")
                .vehiclePlate("29C-123.45")
                .departureTime(LocalDateTime.now())
                .expectedCompletionTime(LocalDateTime.now().plusHours(3))
                .stops(List.of(
                        CreateDeliveryTripStopRequest.builder()
                                .receiverName("Đại lý Mai Linh")
                                .deliveryAddress("123 Phố Huế, Hà Nội")
                                .deliveryAmount(new BigDecimal("15000000.00"))
                                .build(),
                        CreateDeliveryTripStopRequest.builder()
                                .receiverName("Cửa hàng An Bình")
                                .deliveryAddress("456 Cầu Giấy, Hà Nội")
                                .deliveryAmount(new BigDecimal("25000000.00"))
                                .build()
                ))
                .build();

        DeliveryTripResponse res = tripService.createTrip(req, "admin");

        assertThat(res).isNotNull();
        assertThat(res.getStatus()).isEqualTo(DeliveryTripStatus.IN_PROGRESS);
        assertThat(res.getTotalPoints()).isEqualTo(2);
        assertThat(res.getDeliveredPoints()).isEqualTo(0);
        assertThat(res.getRemainingPoints()).isEqualTo(2);
        assertThat(res.getTotalValue()).isEqualByComparingTo(new BigDecimal("40000000.00"));
        assertThat(res.getRemainingValue()).isEqualByComparingTo(new BigDecimal("40000000.00")); // Toàn bộ đang trên đường
        assertThat(res.getIsOverdue()).isFalse();

        verify(auditLogService).logAction(eq("DELIVERY_TRIP"), anyString(), eq("CREATE_TRIP"), any(), any(), any(), eq("admin"));
    }

    @Test
    @DisplayName("Tạo chuyến thất bại: Kho ngoài phạm vi được giao -> 403 AccessDeniedException")
    void createTrip_WarehouseOutOfScope_ThrowsAccessDenied() {
        setAuth("staff_hn", "ROLE_WAREHOUSE_STAFF", "WAREHOUSE_EXPORT");
        when(userRepository.findByUsername("staff_hn")).thenReturn(Optional.of(staffUser));

        CreateDeliveryTripRequest req = CreateDeliveryTripRequest.builder()
                .warehouseId(2L) // Kho HCM (trong khi staff chỉ được giao Kho HN)
                .driverName("Tài xế")
                .vehiclePlate("51A-999.99")
                .expectedCompletionTime(LocalDateTime.now().plusHours(2))
                .build();

        assertThatThrownBy(() -> tripService.createTrip(req, "staff_hn"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Kho này không thuộc phạm vi được giao");
    }

    @Test
    @DisplayName("Tạo chuyến thất bại: Thời gian dự kiến trước thời gian xuất phát")
    void createTrip_ExpectedTimeBeforeDeparture_ThrowsException() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse1));

        CreateDeliveryTripRequest req = CreateDeliveryTripRequest.builder()
                .warehouseId(1L)
                .driverName("Tài xế")
                .vehiclePlate("29C-111.11")
                .departureTime(LocalDateTime.now())
                .expectedCompletionTime(LocalDateTime.now().minusHours(1)) // Trước giờ khởi hành
                .build();

        assertThatThrownBy(() -> tripService.createTrip(req, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thời gian dự kiến hoàn thành phải sau thời điểm xuất phát");
    }

    // ─────────────────────────────────────────────────────────────
    // 3. TIẾN TRÌNH GIAO HÀNG & INVARIANT HÀNG KHÔNG TỰ VỀ KHO (S6-07)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Giao thành công 1 điểm: deliveredPoints = 1, remainingPoints = 1, cập nhật tiền trên đường")
    void updateStopStatus_Delivered_UpdatesProgressAndValueOnRoad() {
        DeliveryTrip trip = createSampleTrip();
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(DeliveryTrip.class))).thenAnswer(i -> i.getArgument(0));

        UpdateDeliveryStopStatusRequest req = UpdateDeliveryStopStatusRequest.builder()
                .status(DeliveryStopStatus.DELIVERED)
                .note("Đã ký nhận đầy đủ")
                .build();

        DeliveryTripResponse res = tripService.updateStopStatus(10L, 101L, req, "admin");

        assertThat(res.getDeliveredPoints()).isEqualTo(1);
        assertThat(res.getRemainingPoints()).isEqualTo(1);
        assertThat(res.getDeliveredValue()).isEqualByComparingTo(new BigDecimal("15000000.00"));
        assertThat(res.getRemainingValue()).isEqualByComparingTo(new BigDecimal("25000000.00")); // Còn 25tr trên đường
    }

    @Test
    @DisplayName("QUY TẮC CỐT LÕI S6-07: Hàng chưa giao được vẫn thuộc trách nhiệm chuyến, KHÔNG TỰ VỀ KHO")
    void updateStopStatus_Failed_DoesNotReturnToWarehouse_RemainsOnRoad() {
        DeliveryTrip trip = createSampleTrip();
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(DeliveryTrip.class))).thenAnswer(i -> i.getArgument(0));

        UpdateDeliveryStopStatusRequest req = UpdateDeliveryStopStatusRequest.builder()
                .status(DeliveryStopStatus.FAILED)
                .failedReason("Cửa hàng đóng cửa, hẹn giao lại sáng mai")
                .build();

        DeliveryTripResponse res = tripService.updateStopStatus(10L, 101L, req, "admin");

        // 1. Kiểm tra trạng thái điểm
        DeliveryTripStop stop1 = trip.getStops().get(0);
        assertThat(stop1.getStatus()).isEqualTo(DeliveryStopStatus.FAILED);
        assertThat(stop1.getFailedReason()).isEqualTo("Cửa hàng đóng cửa, hẹn giao lại sáng mai");
        assertThat(stop1.getReturnedToWarehouse()).isFalse(); // KHÔNG tự về kho!

        // 2. KHÔNG BAO GIỜ gọi repository tồn kho để cộng lại tồn!
        verifyNoInteractions(inventoryRepository);

        // 3. Tiền hàng chưa giao được VẪN TÍNH LÀ TIỀN HÀNG TRÊN ĐƯỜNG (thuộc trách nhiệm chuyến)
        // Tổng tiền 40tr: 0 điểm đã giao, 2 điểm còn lại (1 pending + 1 failed)
        // remainingValue vẫn là 40tr vì hàng chưa về kho!
        assertThat(res.getDeliveredPoints()).isEqualTo(0);
        assertThat(res.getFailedPoints()).isEqualTo(1);
        assertThat(res.getRemainingPoints()).isEqualTo(2); // Vẫn còn 2 điểm chưa giao xong
        assertThat(res.getFailedValue()).isEqualByComparingTo(new BigDecimal("15000000.00"));
        assertThat(res.getRemainingValue()).isEqualByComparingTo(new BigDecimal("40000000.00"));

        // 4. Audit ghi nhận rõ hàng không tự về kho
        verify(auditLogService).logAction(eq("DELIVERY_TRIP"), eq(trip.getTripCode()), eq("STOP_FAILED"),
                isNull(), eq("FAILED"),
                contains("Hàng vẫn thuộc trách nhiệm của chuyến, không tự động về kho"),
                eq("admin"));
    }

    @Test
    @DisplayName("Cập nhật điểm FAILED nhưng thiếu lý do -> ném ngoại lệ IllegalArgumentException")
    void updateStopStatus_FailedWithoutReason_ThrowsException() {
        DeliveryTrip trip = createSampleTrip();
        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));

        UpdateDeliveryStopStatusRequest req = UpdateDeliveryStopStatusRequest.builder()
                .status(DeliveryStopStatus.FAILED)
                .failedReason("") // Rỗng
                .build();

        assertThatThrownBy(() -> tripService.updateStopStatus(10L, 101L, req, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bắt buộc phải nhập lý do khi điểm giao chưa giao được");
    }

    // ─────────────────────────────────────────────────────────────
    // 4. CẢNH BÁO QUÁ THỜI GIAN DỰ KIẾN (S6-07)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cảnh báo chuyến quá thời gian dự kiến khi đang chạy và hiện tại > expectedCompletionTime")
    void tripOverdue_GeneratesWarningAndCalculatesMinutes() {
        DeliveryTrip trip = createSampleTrip();
        // Đặt giờ dự kiến là 45 phút trước
        trip.setExpectedCompletionTime(LocalDateTime.now().minusMinutes(45));

        assertThat(trip.isOverdue()).isTrue();
        assertThat(trip.getOverdueMinutes()).isGreaterThanOrEqualTo(44); // Dung sai vài giây

        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        DeliveryTripResponse res = tripService.getTripById(10L);

        assertThat(res.getIsOverdue()).isTrue();
        assertThat(res.getOverdueMinutes()).isGreaterThanOrEqualTo(44);
        assertThat(res.getOverdueWarning()).contains("quá thời gian dự kiến");
    }

    @Test
    @DisplayName("Chuyến chưa tới giờ dự kiến -> Không cảnh báo quá hạn")
    void tripNotOverdue_NoWarning() {
        DeliveryTrip trip = createSampleTrip();
        trip.setExpectedCompletionTime(LocalDateTime.now().plusHours(2));

        assertThat(trip.isOverdue()).isFalse();
        assertThat(trip.getOverdueMinutes()).isEqualTo(0L);

        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        DeliveryTripResponse res = tripService.getTripById(10L);

        assertThat(res.getIsOverdue()).isFalse();
        assertThat(res.getOverdueWarning()).isNull();
    }

    // ─────────────────────────────────────────────────────────────
    // 5. THỐNG KÊ TỔNG QUAN TIỀN HÀNG TRÊN ĐƯỜNG (S6-07 SUMMARY)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Summary: Tính chính xác tổng tiền hàng đang nằm trên đường từ các chuyến đang chạy")
    void getSummary_CalculatesAccurateTotalValueOnRoad() {
        DeliveryTrip trip1 = createSampleTrip(); // 40tr tổng, 0 delivered, 40tr on road
        DeliveryTrip trip2 = createSampleTrip(); // Chuyến thứ 2
        trip2.setId(20L);
        trip2.setTripCode("CG-20261022-002");
        trip2.setExpectedCompletionTime(LocalDateTime.now().minusMinutes(30)); // Quá hạn 30p
        // Trip 2 đã giao xong điểm 1 (15tr), còn lại 25tr trên đường
        trip2.getStops().get(0).setStatus(DeliveryStopStatus.DELIVERED);

        when(warehouseRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(warehouse1, warehouse2));
        when(tripRepository.findAllByWarehouseIdInAndStatus(any(), eq(DeliveryTripStatus.IN_PROGRESS)))
                .thenReturn(List.of(trip1, trip2));

        DeliveryTripSummaryResponse summary = tripService.getSummary(null);

        assertThat(summary.getTotalRunningTrips()).isEqualTo(2);
        assertThat(summary.getTotalOverdueTrips()).isEqualTo(1); // trip2 quá hạn
        assertThat(summary.getTotalTripValue()).isEqualByComparingTo(new BigDecimal("80000000.00"));
        assertThat(summary.getTotalDeliveredValue()).isEqualByComparingTo(new BigDecimal("15000000.00"));
        // Tiền hàng trên đường = 40tr (trip1) + 25tr (trip2) = 65tr
        assertThat(summary.getTotalValueOnRoad()).isEqualByComparingTo(new BigDecimal("65000000.00"));
        assertThat(summary.getTotalPoints()).isEqualTo(4);
        assertThat(summary.getTotalDeliveredPoints()).isEqualTo(1);
        assertThat(summary.getTotalRemainingPoints()).isEqualTo(3);
    }

    // ─────────────────────────────────────────────────────────────
    // 6. NHẬP KHO TRẢ HÀNG TƯỜNG MINH (EXPLICIT RETURN)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Nhập kho trả hàng tường minh: Chỉ khi gọi API này mới cộng lại tồn kho")
    void returnStopGoodsToWarehouse_ExplicitAction_RestocksInventory() {
        DeliveryTrip trip = createSampleTrip();
        DeliveryTripStop stop1 = trip.getStops().get(0);
        stop1.setStatus(DeliveryStopStatus.FAILED);
        stop1.setFailedReason("Khách không nhận");
        stop1.setReturnedToWarehouse(false);

        // Thêm mặt hàng vào điểm giao
        Product product = Product.builder().id(50L).code("BIA-SG").name("Bia Sài Gòn Special").build();
        DeliveryTripStopItem item = DeliveryTripStopItem.builder()
                .id(501L)
                .stop(stop1)
                .product(product)
                .quantity(new BigDecimal("10.0000"))
                .unitPrice(new BigDecimal("300000.00"))
                .totalPrice(new BigDecimal("3000000.00"))
                .build();
        stop1.getItems().add(item);

        WarehouseInventory inv = WarehouseInventory.builder()
                .id(1L)
                .warehouse(warehouse1)
                .product(product)
                .baseQuantity(new BigDecimal("100.0000"))
                .build();

        when(tripRepository.findById(10L)).thenReturn(Optional.of(trip));
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 50L)).thenReturn(Optional.of(inv));
        when(stopRepository.save(any(DeliveryTripStop.class))).thenAnswer(i -> i.getArgument(0));

        ReturnStopGoodsRequest req = ReturnStopGoodsRequest.builder()
                .returnReason("Thủ kho đã nhận lại 10 thùng hàng về kho")
                .build();

        DeliveryTripStopResponse res = tripService.returnStopGoodsToWarehouse(10L, 101L, req, "admin");

        assertThat(res.getReturnedToWarehouse()).isTrue();
        // Kiểm tra tồn kho được cộng lại đúng 10 đơn vị (100 + 10 = 110)
        assertThat(inv.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("110.0000"));
        verify(inventoryRepository).save(inv);
        verify(auditLogService).logAction(eq("DELIVERY_TRIP"), eq(trip.getTripCode()), eq("RETURN_TO_WAREHOUSE"),
                eq("false"), eq("true"), anyString(), eq("admin"));
    }

    private DeliveryTrip createSampleTrip() {
        DeliveryTrip trip = DeliveryTrip.builder()
                .id(10L)
                .tripCode("CG-20261022-001")
                .warehouse(warehouse1)
                .driverName("Tài xế A")
                .vehiclePlate("29C-123.45")
                .status(DeliveryTripStatus.IN_PROGRESS)
                .departureTime(LocalDateTime.now().minusHours(1))
                .expectedCompletionTime(LocalDateTime.now().plusHours(2))
                .stops(new ArrayList<>())
                .build();

        DeliveryTripStop stop1 = DeliveryTripStop.builder()
                .id(101L)
                .trip(trip)
                .receiverName("Đại lý 1")
                .deliveryAddress("Địa chỉ 1")
                .stopOrder(1)
                .status(DeliveryStopStatus.PENDING)
                .deliveryAmount(new BigDecimal("15000000.00"))
                .returnedToWarehouse(false)
                .items(new ArrayList<>())
                .build();

        DeliveryTripStop stop2 = DeliveryTripStop.builder()
                .id(102L)
                .trip(trip)
                .receiverName("Đại lý 2")
                .deliveryAddress("Địa chỉ 2")
                .stopOrder(2)
                .status(DeliveryStopStatus.PENDING)
                .deliveryAmount(new BigDecimal("25000000.00"))
                .returnedToWarehouse(false)
                .items(new ArrayList<>())
                .build();

        trip.addStop(stop1);
        trip.addStop(stop2);
        return trip;
    }
}
