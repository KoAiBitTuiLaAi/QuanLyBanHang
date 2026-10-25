package com.quanlybanhang.service;

import com.quanlybanhang.dto.PickingBatchDtos.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.model.Order;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class PickingBatchService {
    private final PickingBatchRepository batches;
    private final PickingOrderClaimRepository claims;
    private final WarehouseRepository warehouses;
    private final WarehouseLocationProductRepository locations;
    private final OrderRepository orders;
    private final UserRepository users;
    private final OrderService orderService;
    private final AuditLogService audit;
    private static final Set<String> ELIGIBLE = Set.of("RECORDED", "APPROVED", "PICKING", "PROCESSING");
    private static final Set<String> STATUSES = Set.of("IN_PROGRESS", "COMPLETED", "CANCELLED");
    private static final Pattern PARTS = Pattern.compile("\\d+|\\D+");

    private record Access(boolean admin, boolean write, Set<Long> warehouses, String username) {
        boolean contains(Long id) { return admin || warehouses.contains(id); }
        void require(Long id) { if (!contains(id)) throw new AccessDeniedException("Kho không thuộc phạm vi được phân công"); }
    }
    private Access access(boolean write) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()))
            throw new AccessDeniedException("Bạn chưa đăng nhập");
        Set<String> grants = new HashSet<>(); auth.getAuthorities().forEach(a -> grants.add(a.getAuthority()));
        boolean admin = grants.contains("ROLE_ADMIN"), mutate = admin || grants.contains("WAREHOUSE_EXPORT");
        if (write ? !mutate : !mutate && !grants.contains("WAREHOUSE_VIEW"))
            throw new AccessDeniedException("Bạn không có quyền xem hoặc thực hiện soạn hàng theo ma trận");
        if (admin) return new Access(true, true, Set.of(), auth.getName());
        User user = users.findByUsername(auth.getName()).orElseThrow(() -> new AccessDeniedException("Không tìm thấy tài khoản"));
        Set<Long> ids = new HashSet<>();
        if (user.getWarehouses() != null) user.getWarehouses().forEach(w -> ids.add(w.getId()));
        return new Access(false, mutate, ids, auth.getName());
    }
    public Options options() {
        Access access = access(false);
        return new Options(warehouses.findAllByActiveTrueOrderByNameAsc().stream().filter(w -> access.contains(w.getId()))
                .map(w -> new Named(w.getId(), w.getCode(), w.getName())).toList(),
                List.of(new Status("IN_PROGRESS", "Đang soạn"), new Status("COMPLETED", "Hoàn tất"), new Status("CANCELLED", "Đã hủy")),
                access.write(), 100, 500, 4, "Theo thứ tự đường đi của khu/kệ; chưa khai báo thì theo mã tự nhiên; chưa gán vị trí xếp cuối");
    }
    public Page<EligibleOrder> eligible(Long warehouseId, String keyword, int page, int size) {
        Access access = access(false); access.require(warehouseId); warehouse(warehouseId);
        String term = term(keyword); LocalDateTime now = LocalDateTime.now();
        Specification<Order> filter = (root, query, cb) -> {
            var claim = query.subquery(Long.class); var c = claim.from(PickingOrderClaim.class);
            claim.select(c.get("id")).where(cb.equal(c.get("order").get("id"), root.get("id")));
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            predicates.add(root.get("status").in(ELIGIBLE));
            predicates.add(cb.isTrue(root.get("inventoryReserved")));
            predicates.add(cb.or(cb.isNull(root.get("reservationExpiresAt")), cb.greaterThan(root.get("reservationExpiresAt"), now)));
            predicates.add(cb.isNotEmpty(root.get("items"))); predicates.add(cb.not(cb.exists(claim)));
            if (term != null) predicates.add(cb.like(cb.lower(root.get("orderCode")), term, '!'));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return orders.findAll(filter, paging(page, size)).map(o -> new EligibleOrder(o.getId(), o.getOrderCode(),
                o.getCustomerName(), o.getStatus(), o.getExpectedDeliveryDate(), o.getItems().size()));
    }
    public Page<Summary> search(Long warehouseId, String status, String keyword, int page, int size) {
        Access access = access(false);
        if (warehouseId != null) { positive(warehouseId); access.require(warehouseId); }
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw bad("Trạng thái phiếu không hợp lệ");
        String term = term(keyword);
        return batches.findAll((Specification<PickingBatch>)(root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (!access.admin()) predicates.add(access.warehouses().isEmpty() ? cb.disjunction() : root.get("warehouse").get("id").in(access.warehouses()));
            if (warehouseId != null) predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            if (status != null && !status.isBlank()) predicates.add(cb.equal(root.get("status"), status));
            if (term != null) predicates.add(cb.like(cb.lower(root.get("code")), term, '!'));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, paging(page, size)).map(this::summary);
    }
    public Detail get(Long id) { Access access = access(false); return detail(load(id, access, false), access); }

    @Transactional public Detail create(Create request) {
        Access access = access(true); access.require(request.warehouseId()); Warehouse warehouse = warehouse(request.warehouseId());
        if (request.orderIds() == null || request.orderIds().isEmpty() || request.orderIds().size() > 100
                || new HashSet<>(request.orderIds()).size() != request.orderIds().size()) throw bad("Chọn 1–100 đơn, không lặp đơn");
        List<Order> selected = request.orderIds().stream().sorted().map(id -> {
            positive(id); Order o = orders.findByIdForUpdate(id).orElseThrow(() -> bad("Không tìm thấy đơn hàng"));
            if (o.getWarehouse() == null || !warehouse.getId().equals(o.getWarehouse().getId())) throw bad("Các đơn phải cùng kho soạn");
            validOrder(o);
            if (claims.existsByOrderId(id)) throw bad("Đơn " + o.getOrderCode() + " đã thuộc một lượt soạn khác");
            return o;
        }).toList();
        int total = selected.stream().mapToInt(o -> o.getItems().size()).sum();
        if (total < 1 || total > 500) throw bad("Lượt soạn phải có từ 1 đến 500 dòng hàng");
        String code = text(request.code(), 50); if (code == null) code = "PSH-" + UUID.randomUUID();
        if (batches.existsByCode(code)) throw bad("Mã phiếu soạn đã tồn tại");
        LocalDateTime now = LocalDateTime.now();
        PickingBatch batch = PickingBatch.builder().code(code).warehouse(warehouse).status("IN_PROGRESS")
                .note(text(request.note(), 500)).createdBy(access.username()).createdAt(now).updatedAt(now).build();
        Map<PickingBatchItem, WarehouseLocation> route = new IdentityHashMap<>();
        for (Order order : selected) for (OrderItem line : order.getItems()) {
            if (line.getProduct() == null || !Boolean.TRUE.equals(line.getProduct().getActive())) throw bad("Dòng hàng không còn kinh doanh");
            BigDecimal qty = quantity(line.getQuantity());
            if (qty.signum() <= 0 || line.getConversionFactor() == null || line.getConversionFactor().signum() <= 0)
                throw bad("Dòng hàng có số lượng hoặc quy đổi không hợp lệ");
            WarehouseLocation location = locations.findByWarehouseIdAndProductId(warehouse.getId(), line.getProduct().getId()).stream()
                    .map(WarehouseLocationProduct::getLocation).filter(l -> validLocation(l, warehouse.getId())).findFirst().orElse(null);
            PickingBatchItem item = PickingBatchItem.builder().batch(batch).order(order).orderItem(line).orderCode(order.getOrderCode())
                    .productCode(line.getProduct().getCode()).productName(line.getProduct().getName()).unit(line.getUnit())
                    .baseUnit(line.getBaseUnit()).quantity(qty).conversionFactor(line.getConversionFactor())
                    .locationId(location == null ? null : location.getId()).locationCode(location == null ? null : location.getCode())
                    .locationDisplay(location == null ? "Chưa gán vị trí" : locationDisplay(location)).build();
            batch.getItems().add(item); route.put(item, location);
        }
        batch.getItems().sort((a, b) -> {
            int compared = compareLocations(route.get(a), route.get(b));
            if (compared == 0) compared = natural(a.getProductCode(), b.getProductCode());
            if (compared == 0) compared = a.getOrder().getId().compareTo(b.getOrder().getId());
            return compared == 0 ? a.getOrderItem().getId().compareTo(b.getOrderItem().getId()) : compared;
        });
        for (int i = 0; i < batch.getItems().size(); i++) batch.getItems().get(i).setRoutePosition(i + 1);
        batches.saveAndFlush(batch);
        for (Order order : selected) {
            claims.saveAndFlush(PickingOrderClaim.builder().order(order).batch(batch).build());
            if (!Set.of("PICKING", "PROCESSING").contains(order.getStatus()))
                orderService.updateOrderStatus(order.getId(), "PICKING", "Lượt soạn " + code, access.username());
        }
        audit.logAction("PICKING_BATCH", code, "CREATE", null, "IN_PROGRESS", "Tạo lượt soạn " + selected.size() + " đơn", access.username());
        return detail(batch, access);
    }
    @Transactional public Detail update(Long id, Long itemId, UpdateLine request) {
        Access access = access(true); PickingBatch batch = load(id, access, true); editable(batch, request.version()); validateCurrentOrders(batch);
        PickingBatchItem line = batch.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst().orElseThrow(() -> bad("Dòng hàng không thuộc phiếu soạn"));
        BigDecimal qty = request.pickedQuantity() == null ? null : quantity(request.pickedQuantity());
        String reason = text(request.shortageReason(), 500);
        validateLine(line, qty, Boolean.TRUE.equals(request.checked()), reason);
        line.setPickedQuantity(qty); line.setChecked(request.checked()); line.setShortageReason(reason);
        batch.setUpdatedAt(LocalDateTime.now()); batches.saveAndFlush(batch);
        audit.logAction("PICKING_BATCH", batch.getCode(), "SAVE_LINE", null, String.valueOf(qty), "Dòng " + line.getId(), access.username());
        return detail(batch, access);
    }
    @Transactional public Detail complete(Long id, Long version) {
        Access access = access(true); PickingBatch batch = load(id, access, true); editable(batch, version); validateCurrentOrders(batch);
        for (PickingBatchItem line : batch.getItems()) {
            if (!Boolean.TRUE.equals(line.getChecked())) throw bad("Cần đánh dấu đã soạn và lưu đủ các dòng trước khi hoàn tất");
            validateLine(line, line.getPickedQuantity(), true, line.getShortageReason());
        }
        batch.setStatus("COMPLETED"); batch.setCompletedAt(LocalDateTime.now()); batch.setUpdatedAt(LocalDateTime.now());
        batches.saveAndFlush(batch);
        audit.logAction("PICKING_BATCH", batch.getCode(), "COMPLETE", "IN_PROGRESS", "COMPLETED", "Hoàn tất soạn, không tự xuất kho", access.username());
        return detail(batch, access);
    }
    @Transactional public Detail cancel(Long id, Cancel request) {
        Access access = access(true); PickingBatch batch = load(id, access, true); editable(batch, request.version());
        String reason = text(request.reason(), 500); if (reason == null) throw bad("Bắt buộc nhập lý do hủy lượt soạn");
        batch.setStatus("CANCELLED"); batch.setCancelReason(reason); batch.setUpdatedAt(LocalDateTime.now());
        claims.deleteAllByBatchId(id); claims.flush(); batches.saveAndFlush(batch);
        audit.logAction("PICKING_BATCH", batch.getCode(), "CANCEL", "IN_PROGRESS", "CANCELLED", reason, access.username());
        return detail(batch, access);
    }
    private void validateCurrentOrders(PickingBatch batch) {
        warehouse(batch.getWarehouse().getId());
        for (Long id : batch.getItems().stream().map(i -> i.getOrder().getId()).distinct().sorted().toList()) {
            Order order = orders.findByIdForUpdate(id).orElseThrow(() -> bad("Đơn gốc không còn tồn tại"));
            if (order.getWarehouse() == null || !batch.getWarehouse().getId().equals(order.getWarehouse().getId()))
                throw bad("Kho của đơn gốc đã thay đổi; cần đối soát và tạo lượt soạn mới");
            validOrder(order);
        }
    }
    private boolean usableOrder(Order order) {
        return ELIGIBLE.contains(order.getStatus()) && Boolean.TRUE.equals(order.getInventoryReserved())
                && (order.getReservationExpiresAt() == null || order.getReservationExpiresAt().isAfter(LocalDateTime.now()));
    }
    private void validOrder(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) throw bad("Đơn không có dòng hàng để soạn");
        if (!usableOrder(order)) throw bad("Đơn " + order.getOrderCode() + " chưa duyệt/không còn giữ chỗ hoặc đã hết hạn/đã xuất; không thể tiếp tục soạn");
    }
    private void validateLine(PickingBatchItem line, BigDecimal qty, boolean checked, String reason) {
        if (checked && qty == null) throw bad("Đánh dấu đã soạn phải nhập số lượng thực soạn");
        if (qty == null) return;
        quantity(qty);
        if (qty.compareTo(line.getQuantity()) > 0) throw bad("Thực soạn không được lớn hơn số lượng yêu cầu");
        if (qty.compareTo(line.getQuantity()) < 0 && (reason == null || reason.isBlank())) throw bad("Soạn thiếu bắt buộc nhập lý do");
        quantity(qty.multiply(line.getConversionFactor()));
    }
    private PickingBatch load(Long id, Access access, boolean lock) {
        positive(id);
        PickingBatch batch = (lock ? batches.findByIdForUpdate(id) : batches.findById(id)).orElseThrow(() -> bad("Không tìm thấy phiếu soạn"));
        access.require(batch.getWarehouse().getId()); return batch;
    }
    private void editable(PickingBatch batch, Long version) {
        if (!Objects.equals(batch.getVersion(), version)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiếu đã thay đổi. Tải lại trước khi sửa; dữ liệu nhập chưa được lưu");
        if (!"IN_PROGRESS".equals(batch.getStatus())) throw bad("Chỉ được sửa phiếu đang soạn");
    }
    private Warehouse warehouse(Long id) {
        positive(id); Warehouse warehouse = warehouses.findById(id).orElseThrow(() -> bad("Không tìm thấy kho"));
        if (!Boolean.TRUE.equals(warehouse.getActive())) throw bad("Kho đã ngừng hoạt động"); return warehouse;
    }
    private Summary summary(PickingBatch b) {
        return new Summary(b.getId(), b.getVersion(), b.getCode(), b.getWarehouse().getId(), b.getWarehouse().getCode(), b.getWarehouse().getName(),
                b.getStatus(), switch (b.getStatus()) { case "COMPLETED" -> "Hoàn tất"; case "CANCELLED" -> "Đã hủy"; default -> "Đang soạn"; },
                (int)b.getItems().stream().map(i -> i.getOrder().getId()).distinct().count(), b.getItems().size(),
                (int)b.getItems().stream().filter(i -> Boolean.TRUE.equals(i.getChecked())).count(),
                (int)b.getItems().stream().filter(i -> i.getPickedQuantity() != null && i.getPickedQuantity().compareTo(i.getQuantity()) < 0).count(), b.getCreatedAt(), b.getCreatedBy());
    }
    private Detail detail(PickingBatch b, Access access) {
        boolean open = "IN_PROGRESS".equals(b.getStatus());
        boolean valid = Boolean.TRUE.equals(b.getWarehouse().getActive()) && b.getItems().stream().allMatch(i -> usableOrder(i.getOrder())
                && i.getOrder().getWarehouse() != null && b.getWarehouse().getId().equals(i.getOrder().getWarehouse().getId()));
        boolean edit = open && valid && access.write();
        return new Detail(summary(b), b.getNote(), b.getCancelReason(), b.getCompletedAt(), b.getItems().stream().map(i -> new Line(
                i.getId(), i.getOrder().getId(), i.getOrderCode(), i.getOrderItem().getProduct().getId(), i.getProductCode(), i.getProductName(),
                i.getUnit(), i.getBaseUnit(), i.getQuantity(), i.getConversionFactor(), i.getPickedQuantity(),
                i.getPickedQuantity() == null ? null : i.getPickedQuantity().multiply(i.getConversionFactor()), i.getChecked(),
                i.getPickedQuantity() == null ? null : i.getQuantity().subtract(i.getPickedQuantity()), i.getShortageReason(),
                i.getLocationId(), i.getLocationCode(), i.getLocationDisplay(), i.getRoutePosition())).toList(), edit,
                edit && b.getItems().stream().allMatch(i -> Boolean.TRUE.equals(i.getChecked())), open && access.write(),
                open && !valid ? "Kho ngừng hoạt động hoặc đơn gốc đã hủy/hết hạn/đã xuất. Hãy đối soát và hủy lượt soạn này." : null);
    }
    private static boolean validLocation(WarehouseLocation location, Long warehouseId) {
        Set<Long> seen = new HashSet<>();
        for (var current = location; current != null; current = current.getParent())
            if (!seen.add(current.getId()) || !Boolean.TRUE.equals(current.getActive()) || !warehouseId.equals(current.getWarehouse().getId())) return false;
        return location != null;
    }
    private static List<WarehouseLocation> path(WarehouseLocation location) {
        var result = new ArrayList<WarehouseLocation>();
        for (var current = location; current != null; current = current.getParent()) result.add(current);
        Collections.reverse(result); return result;
    }
    private static String locationDisplay(WarehouseLocation location) {
        return String.join(" > ", path(location).stream().map(WarehouseLocation::getName).toList());
    }
    private static int compareLocations(WarehouseLocation a, WarehouseLocation b) {
        if (a == null || b == null) return a == b ? 0 : a == null ? 1 : -1;
        var left = path(a); var right = path(b);
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            var x = left.get(i); var y = right.get(i);
            int compared = Integer.compare(x.getPickingSequence() == null ? Integer.MAX_VALUE : x.getPickingSequence(), y.getPickingSequence() == null ? Integer.MAX_VALUE : y.getPickingSequence());
            if (compared == 0) compared = natural(x.getCode(), y.getCode());
            if (compared != 0) return compared;
        }
        return Integer.compare(left.size(), right.size());
    }
    private static int natural(String a, String b) {
        var left = PARTS.matcher(a.toLowerCase(Locale.ROOT)); var right = PARTS.matcher(b.toLowerCase(Locale.ROOT));
        while (left.find()) {
            if (!right.find()) return 1;
            String x = left.group(), y = right.group();
            int result = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0)) ? new BigInteger(x).compareTo(new BigInteger(y)) : x.compareTo(y);
            if (result != 0) return result;
        }
        return right.find() ? -1 : a.compareToIgnoreCase(b);
    }
    private static Pageable paging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw bad("Phân trang không hợp lệ");
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }
    private static String term(String value) { String text = text(value, 150); return text == null ? null : "%" + text.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    private static String text(String value, int max) { if (value == null || value.isBlank()) return null; if (value.length() > max) throw bad("Nội dung vượt độ dài cho phép"); return value.trim(); }
    private static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 4 || value.compareTo(new BigDecimal("99999999999.9999")) > 0)
            throw bad("Số lượng phải không âm, tối đa 11 chữ số và 4 số thập phân"); return value;
    }
    private static void positive(Long value) { if (value == null || value < 1) throw bad("ID không hợp lệ"); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
