package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.request.CancelDealerReturnRequest;
import com.quanlybanhang.dto.request.ConfirmDealerReturnRequest;
import com.quanlybanhang.dto.request.CreateDealerReturnItemRequest;
import com.quanlybanhang.dto.request.CreateDealerReturnRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import com.quanlybanhang.service.DealerReturnService;
import com.quanlybanhang.service.UnitConversionService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Triển khai nghiệp vụ Lập phiếu trả hàng từ đại lý và nhập lại kho (S8-01).
 *
 * Tiêu chí nghiệm thu:
 * 1. Chọn hoá đơn gốc, chọn dòng hàng và số lượng trả, bắt buộc chọn lý do trả.
 * 2. Số lượng trả không vượt số lượng đã giao trừ phần đã trả trước đó.
 * 3. Hàng trả về nhập lại đúng lô nếu SKU có quản lý lô; hàng hỏng nhập vào kho hàng lỗi.
 * 4. Phiếu trả phải được Quản lý kho xác nhận nhận hàng mới cộng tồn.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DealerReturnServiceImpl implements DealerReturnService {

    private static final List<DealerReturnStatus> ACTIVE_RETURN_STATUSES = List.of(
            DealerReturnStatus.PENDING,
            DealerReturnStatus.CONFIRMED
    );

    private static final Set<String> DEFECTIVE_REASON_CODES = Set.of(
            "DAMAGED", "DEFECTIVE", "PACKAGING_DEFECT", "BROKEN", "EXPIRED", "HANG_HONG", "HANG_LOI"
    );

    private final DealerReturnRepository dealerReturnRepository;
    private final DealerReturnItemRepository dealerReturnItemRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrderItemRepository orderItemRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseInventoryRepository inventoryRepository;
    private final WarehouseLotInventoryRepository lotInventoryRepository;
    private final UserRepository userRepository;
    private final UnitConversionService unitConversionService;
    private final AuditLogService auditLogService;

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. KIỂM TRA QUYỀN MA TRẬN DB & PHẠM VI DỮ LIỆU (DATA SCOPE)
    // ═══════════════════════════════════════════════════════════════════════════

    private Set<String> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("Bạn chưa đăng nhập");
        }
        Set<String> grants = new HashSet<>();
        if (auth.getAuthorities() != null) {
            auth.getAuthorities().forEach(a -> grants.add(a.getAuthority()));
        }
        return grants;
    }

    private String currentUsername(String fallback) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return fallback != null && !fallback.isBlank() ? fallback : "system";
    }

    private void requireAnyPermission(String... permissions) {
        Set<String> grants = currentAuthorities();
        if (grants.contains("ROLE_ADMIN")) {
            return;
        }
        for (String perm : permissions) {
            if (grants.contains(perm)) {
                return;
            }
        }
        throw new AccessDeniedException("Bạn không có quyền thực hiện chức năng này theo ma trận phân quyền");
    }

    private boolean hasAnyPermission(Set<String> grants, String... permissions) {
        if (grants.contains("ROLE_ADMIN")) {
            return true;
        }
        for (String perm : permissions) {
            if (grants.contains(perm)) {
                return true;
            }
        }
        return false;
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || userRepository == null) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    private boolean isSalesScopedOnly(Set<String> grants) {
        if (grants.contains("ROLE_ADMIN")
                || grants.contains("ROLE_SALES_MANAGER")
                || grants.contains("ROLE_WAREHOUSE_MANAGER")
                || grants.contains("ROLE_WAREHOUSE_STAFF")
                || grants.contains("ROLE_ACCOUNTANT")
                || grants.contains("ROLE_VIEWER")) {
            return false;
        }
        return grants.contains("ROLE_SALES");
    }

    private boolean isWarehouseScopedOnly(Set<String> grants) {
        if (grants.contains("ROLE_ADMIN")
                || grants.contains("ROLE_SALES_MANAGER")
                || grants.contains("ROLE_SALES")
                || grants.contains("ROLE_ACCOUNTANT")
                || grants.contains("ROLE_VIEWER")) {
            return false;
        }
        return grants.contains("ROLE_WAREHOUSE_MANAGER") || grants.contains("ROLE_WAREHOUSE_STAFF");
    }

    private void checkSalesScopeForInvoice(Invoice invoice, Set<String> grants, String username) {
        if (!isSalesScopedOnly(grants)) {
            return;
        }
        Dealer dealer = invoice.getDealer();
        if (dealer != null && dealer.getAssignedUser() != null
                && dealer.getAssignedUser().getUsername() != null
                && dealer.getAssignedUser().getUsername().equalsIgnoreCase(username)) {
            return;
        }
        if (invoice.getOrders() != null && invoice.getOrders().stream()
                .anyMatch(o -> o.getCreatedBy() != null && o.getCreatedBy().equalsIgnoreCase(username))) {
            return;
        }
        throw new AccessDeniedException("Bạn chỉ được lập hoặc xem phiếu trả hàng cho hóa đơn của đại lý do mình phụ trách");
    }

    private Set<Long> getAssignedWarehouseIds() {
        User user = getCurrentUser();
        if (user == null || user.getWarehouses() == null) {
            return Set.of();
        }
        return user.getWarehouses().stream()
                .filter(Objects::nonNull)
                .map(Warehouse::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean canAccessWarehouse(Long warehouseId, Set<String> grants) {
        if (grants.contains("ROLE_ADMIN")) {
            return true;
        }
        Set<Long> assignedIds = getAssignedWarehouseIds();
        if (!assignedIds.isEmpty()) {
            return assignedIds.contains(warehouseId);
        }
        return !grants.contains("ROLE_WAREHOUSE_MANAGER") && !grants.contains("ROLE_WAREHOUSE_STAFF");
    }

    private void requireWarehouseScopeForConfirmation(DealerReturn dealerReturn, Set<String> grants) {
        if (grants.contains("ROLE_ADMIN")) {
            return;
        }
        Set<Long> assignedIds = getAssignedWarehouseIds();
        boolean warehouseRole = grants.contains("ROLE_WAREHOUSE_MANAGER") || grants.contains("ROLE_WAREHOUSE_STAFF");
        if (!assignedIds.isEmpty() || warehouseRole) {
            Long mainWhId = dealerReturn.getWarehouse() != null ? dealerReturn.getWarehouse().getId() : null;
            Long defWhId = dealerReturn.getDefectiveWarehouse() != null ? dealerReturn.getDefectiveWarehouse().getId() : null;
            boolean allowed = (mainWhId != null && assignedIds.contains(mainWhId))
                    || (defWhId != null && assignedIds.contains(defWhId));
            if (!allowed) {
                throw new AccessDeniedException("Kho nhận hàng trả không thuộc phạm vi quản lý của bạn");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. OPTIONS & TRA CỨU HÓA ĐƠN GỐC ĐỦ ĐIỀU KIỆN TRẢ HÀNG
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public DealerReturnOptionsResponse getOptions() {
        requireAnyPermission("ORDER_VIEW", "ORDER_CREATE", "INVOICE_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_APPROVE", "WAREHOUSE_IMPORT");
        Set<String> grants = currentAuthorities();

        List<Warehouse> activeWarehouses = warehouseRepository.findAllByActiveTrueOrderByNameAsc();
        List<NamedResourceResponse> normalWarehouses = activeWarehouses.stream()
                .filter(w -> !w.isDefectiveWarehouse())
                .filter(w -> !isWarehouseScopedOnly(grants) || canAccessWarehouse(w.getId(), grants))
                .map(w -> new NamedResourceResponse(w.getId(), w.getCode(), w.getName()))
                .toList();

        List<NamedResourceResponse> defectiveWarehouses = activeWarehouses.stream()
                .filter(Warehouse::isDefectiveWarehouse)
                .map(w -> new NamedResourceResponse(w.getId(), w.getCode(), w.getName()))
                .toList();

        List<DealerReturnOptionsResponse.ReturnReasonOption> reasons = List.of(
                new DealerReturnOptionsResponse.ReturnReasonOption("DAMAGED", "Hàng hỏng / lỗi nhà sản xuất", true),
                new DealerReturnOptionsResponse.ReturnReasonOption("PACKAGING_DEFECT", "Bao bì móp méo / bể vỡ khi vận chuyển", true),
                new DealerReturnOptionsResponse.ReturnReasonOption("WRONG_SPEC", "Giao sai quy cách / chủng loại", false),
                new DealerReturnOptionsResponse.ReturnReasonOption("NEAR_EXPIRY", "Hàng cận hạn sử dụng", false),
                new DealerReturnOptionsResponse.ReturnReasonOption("DEALER_EXCHANGE", "Đổi trả theo thỏa thuận thương mại", false),
                new DealerReturnOptionsResponse.ReturnReasonOption("OTHER", "Lý do khác", false)
        );

        List<DealerReturnOptionsResponse.StatusOption> statuses = Arrays.stream(DealerReturnStatus.values())
                .map(s -> new DealerReturnOptionsResponse.StatusOption(s.name(), s.getDisplayName()))
                .toList();

        return DealerReturnOptionsResponse.builder()
                .warehouses(normalWarehouses)
                .defectiveWarehouses(defectiveWarehouses)
                .returnReasons(reasons)
                .statuses(statuses)
                .canCreate(hasAnyPermission(grants, "ORDER_CREATE"))
                .canConfirm(hasAnyPermission(grants, "WAREHOUSE_APPROVE"))
                .build();
    }

    @Override
    public Page<EligibleReturnInvoiceResponse> getEligibleInvoices(Long dealerId, String keyword, int page, int size) {
        requireAnyPermission("ORDER_VIEW", "ORDER_CREATE", "INVOICE_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_APPROVE");
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("Trang phải không âm; số dòng mỗi trang từ 1 đến 200");
        }
        Set<String> grants = currentAuthorities();
        String username = currentUsername(null);

        Specification<Invoice> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.notEqual(root.get("status"), InvoiceStatus.CANCELLED));

            if (dealerId != null) {
                predicates.add(cb.equal(root.get("dealer").get("id"), dealerId));
            }
            if (isSalesScopedOnly(grants)) {
                User currentUser = getCurrentUser();
                if (currentUser != null) {
                    predicates.add(cb.equal(root.get("dealer").get("assignedUser").get("id"), currentUser.getId()));
                } else {
                    predicates.add(cb.disjunction());
                }
            }
            if (keyword != null && !keyword.isBlank()) {
                String term = "%" + keyword.trim().toLowerCase(Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("invoiceCode")), term, '!'),
                        cb.like(cb.lower(root.get("invoiceNumber")), term, '!'),
                        cb.like(cb.lower(root.get("dealerName")), term, '!'),
                        cb.like(cb.lower(root.get("dealerCode")), term, '!')
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "invoiceDate", "id"));
        return invoiceRepository.findAll(spec, pageable).map(this::mapToEligibleInvoiceResponse);
    }

    @Override
    public EligibleReturnInvoiceResponse getEligibleInvoiceById(Long invoiceId) {
        requireAnyPermission("ORDER_VIEW", "ORDER_CREATE", "INVOICE_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_APPROVE");
        if (invoiceId == null) {
            throw new IllegalArgumentException("ID hóa đơn gốc không được để trống");
        }
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn gốc với ID: " + invoiceId));
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new IllegalArgumentException("Hóa đơn đã hủy không thể lập phiếu trả hàng");
        }
        Set<String> grants = currentAuthorities();
        checkSalesScopeForInvoice(invoice, grants, currentUsername(null));
        return mapToEligibleInvoiceResponse(invoice);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. LẬP PHIẾU TRẢ HÀNG TỪ ĐẠI LÝ (NHÂN VIÊN KINH DOANH - CHƯA CỘNG TỒN)
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public DealerReturnResponse createReturn(CreateDealerReturnRequest request, String username) {
        requireAnyPermission("ORDER_CREATE");
        Set<String> grants = currentAuthorities();
        String actor = currentUsername(username);

        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu phiếu trả hàng không được để trống");
        }
        if (request.getInvoiceId() == null) {
            throw new IllegalArgumentException("Bắt buộc phải chọn hóa đơn gốc");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Phiếu trả hàng phải chọn ít nhất 1 dòng hàng trả");
        }

        // Khóa bi quan hóa đơn gốc để chống tạo vượt số lượng trả khi có nhiều request đồng thời
        Invoice invoice = invoiceRepository.findByIdForUpdate(request.getInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn gốc với ID: " + request.getInvoiceId()));

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new IllegalArgumentException("Hóa đơn gốc '" + invoice.getInvoiceCode() + "' đã bị hủy, không thể lập phiếu trả hàng");
        }

        checkSalesScopeForInvoice(invoice, grants, actor);

        Dealer dealer = invoice.getDealer();
        if (dealer == null) {
            throw new IllegalArgumentException("Hóa đơn gốc không có thông tin đại lý hợp lệ");
        }

        Warehouse normalWarehouse = resolveNormalWarehouse(invoice, request.getWarehouseId());
        if (normalWarehouse.isDefectiveWarehouse()) {
            throw new IllegalArgumentException("Kho thương mại nhận hàng tốt không được là kho hàng lỗi ('" + normalWarehouse.getCode() + "')");
        }

        String returnCode = normalizeReturnCode(request.getReturnCode());
        if (dealerReturnRepository.existsByReturnCode(returnCode)) {
            throw new IllegalArgumentException("Mã phiếu trả hàng '" + returnCode + "' đã tồn tại");
        }

        // Kiểm tra tổng số lượng trả theo từng dòng hóa đơn gốc (gộp các dòng trùng invoiceItemId trong cùng request)
        Map<Long, BigDecimal> requestedByInvoiceItemId = new LinkedHashMap<>();
        List<ResolvedLineInput> resolvedInputs = new ArrayList<>();

        for (int idx = 0; idx < request.getItems().size(); idx++) {
            CreateDealerReturnItemRequest itemReq = request.getItems().get(idx);
            if (itemReq == null) {
                throw new IllegalArgumentException("Dòng hàng trả thứ " + (idx + 1) + " không hợp lệ");
            }

            InvoiceItem invoiceItem = resolveInvoiceItem(invoice, itemReq, idx + 1);
            BigDecimal returnQty = itemReq.getEffectiveReturnQuantity();
            if (returnQty == null || returnQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng trả của sản phẩm '" + invoiceItem.getProductCode() + "' phải lớn hơn 0");
            }
            if (returnQty.stripTrailingZeros().scale() > 4) {
                throw new IllegalArgumentException("Số lượng trả tối đa 4 chữ số thập phân");
            }

            // Tiêu chí 1: Bắt buộc chọn lý do trả
            String itemReason = itemReq.getEffectiveReturnReason();
            if (itemReason == null || itemReason.isBlank()) {
                itemReason = request.getEffectiveReturnReason();
            }
            if (itemReason == null || itemReason.isBlank()) {
                throw new IllegalArgumentException("Bắt buộc chọn lý do trả hàng cho dòng sản phẩm '" + invoiceItem.getProductCode() + "'");
            }

            requestedByInvoiceItemId.merge(invoiceItem.getId(), returnQty, BigDecimal::add);
            resolvedInputs.add(new ResolvedLineInput(invoiceItem, itemReq, returnQty.setScale(4, RoundingMode.HALF_UP), itemReason));
        }

        // Tiêu chí 2: Số lượng trả không vượt số lượng đã giao trừ phần đã trả trước đó
        Map<Long, BigDecimal> previouslyReturnedMap = new HashMap<>();
        for (Map.Entry<Long, BigDecimal> entry : requestedByInvoiceItemId.entrySet()) {
            Long invoiceItemId = entry.getKey();
            BigDecimal totalRequestedForLine = entry.getValue();
            InvoiceItem invoiceItem = invoice.getItems().stream()
                    .filter(ii -> ii.getId().equals(invoiceItemId))
                    .findFirst()
                    .orElseThrow();

            BigDecimal deliveredQty = invoiceItem.getDeliveredQuantity() != null
                    ? invoiceItem.getDeliveredQuantity()
                    : BigDecimal.ZERO;
            BigDecimal alreadyReturned = dealerReturnItemRepository.sumReturnQuantityByInvoiceItemIdAndStatuses(
                    invoiceItemId, ACTIVE_RETURN_STATUSES);
            if (alreadyReturned == null) {
                alreadyReturned = BigDecimal.ZERO;
            }
            previouslyReturnedMap.put(invoiceItemId, alreadyReturned);

            BigDecimal maxReturnable = deliveredQty.subtract(alreadyReturned);
            if (maxReturnable.compareTo(BigDecimal.ZERO) < 0) {
                maxReturnable = BigDecimal.ZERO;
            }

            if (totalRequestedForLine.compareTo(maxReturnable) > 0) {
                throw new IllegalArgumentException(String.format(
                        "Số lượng trả (%s %s) của sản phẩm '%s - %s' vượt quá số lượng tối đa còn được trả (%s %s = đã giao %s - đã trả trước đó %s)",
                        totalRequestedForLine.stripTrailingZeros().toPlainString(),
                        invoiceItem.getUnit(),
                        invoiceItem.getProductCode(),
                        invoiceItem.getProductName(),
                        maxReturnable.stripTrailingZeros().toPlainString(),
                        invoiceItem.getUnit(),
                        deliveredQty.stripTrailingZeros().toPlainString(),
                        alreadyReturned.stripTrailingZeros().toPlainString()
                ));
            }
        }

        DealerReturn dealerReturn = DealerReturn.builder()
                .returnCode(returnCode)
                .invoice(invoice)
                .invoiceCode(invoice.getInvoiceCode())
                .dealer(dealer)
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .warehouse(normalWarehouse)
                .status(DealerReturnStatus.PENDING) // Tiêu chí 4: Chờ Quản lý kho xác nhận nhận hàng mới cộng tồn
                .returnReason(request.getEffectiveReturnReason())
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .createdBy(actor)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        BigDecimal totalReturnQty = BigDecimal.ZERO;
        BigDecimal totalReturnAmount = BigDecimal.ZERO;
        Warehouse resolvedHeaderDefectiveWarehouse = null;
        Map<Long, BigDecimal> runningReturnedTracker = new HashMap<>(previouslyReturnedMap);

        for (ResolvedLineInput lineInput : resolvedInputs) {
            InvoiceItem invoiceItem = lineInput.invoiceItem();
            CreateDealerReturnItemRequest itemReq = lineInput.itemReq();
            BigDecimal returnQty = lineInput.returnQuantity();
            String itemReason = lineInput.returnReason();

            Product product = invoiceItem.getProduct();
            if (product == null) {
                throw new IllegalArgumentException("Không tìm thấy thông tin sản phẩm trên dòng hóa đơn");
            }

            // Lấy hệ số quy đổi đóng băng từ OrderItem gốc (hoặc tính theo UnitConversionService)
            OrderItem orderItem = invoiceItem.getOrderItemId() != null
                    ? orderItemRepository.findById(invoiceItem.getOrderItemId()).orElse(null)
                    : null;
            BigDecimal conversionFactor;
            String baseUnit;
            if (orderItem != null && orderItem.getConversionFactor() != null && orderItem.getConversionFactor().compareTo(BigDecimal.ZERO) > 0) {
                conversionFactor = orderItem.getConversionFactor();
                baseUnit = orderItem.getBaseUnit() != null ? orderItem.getBaseUnit() : product.getUnit();
            } else {
                conversionFactor = unitConversionService.getConversionFactor(product, invoiceItem.getUnit());
                baseUnit = product.getUnit();
            }

            BigDecimal baseReturnQty = returnQty.multiply(conversionFactor).setScale(4, RoundingMode.HALF_UP);

            // Tiêu chí 3a: Xác định tình trạng hàng (hàng tốt vs hàng hỏng) và kho đích
            boolean isDefective = resolveIsDefective(itemReq, itemReason);
            Warehouse targetWarehouse;
            if (isDefective) {
                targetWarehouse = resolveDefectiveWarehouse(
                        itemReq.getTargetWarehouseId() != null ? itemReq.getTargetWarehouseId() : request.getDefectiveWarehouseId(),
                        normalWarehouse
                );
                if (resolvedHeaderDefectiveWarehouse == null) {
                    resolvedHeaderDefectiveWarehouse = targetWarehouse;
                }
            } else {
                if (itemReq.getTargetWarehouseId() != null) {
                    Warehouse customWh = warehouseRepository.findById(itemReq.getTargetWarehouseId())
                            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho nhận hàng với ID: " + itemReq.getTargetWarehouseId()));
                    if (!Boolean.TRUE.equals(customWh.getActive())) {
                        throw new IllegalArgumentException("Kho nhận hàng '" + customWh.getName() + "' đã ngừng hoạt động");
                    }
                    if (customWh.isDefectiveWarehouse()) {
                        throw new IllegalArgumentException("Hàng trả tình trạng tốt không được nhập vào kho hàng lỗi '" + customWh.getName() + "'");
                    }
                    targetWarehouse = customWh;
                } else {
                    targetWarehouse = normalWarehouse;
                }
            }

            // Tiêu chí 3b: Hàng trả về nhập lại đúng lô nếu SKU có quản lý lô
            boolean manageLot = Boolean.TRUE.equals(product.getManageLot());
            String lotNumber = itemReq.getLotNumber() != null && !itemReq.getLotNumber().isBlank()
                    ? itemReq.getLotNumber().trim()
                    : null;
            LocalDate expiryDate = itemReq.getExpiryDate();
            LocalDate mfgDate = itemReq.getManufacturingDate();

            Warehouse itemOriginWarehouse = (invoiceItem.getOrder() != null && invoiceItem.getOrder().getWarehouse() != null)
                    ? invoiceItem.getOrder().getWarehouse()
                    : normalWarehouse;

            if (manageLot) {
                if (lotNumber == null || lotNumber.isBlank()) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName()
                            + "' có quản lý lô, bắt buộc phải chọn/nhập đúng số lô trả về");
                }
                LotMetadata lotMeta = validateAndResolveLotMetadata(itemOriginWarehouse, targetWarehouse, product, lotNumber, expiryDate, mfgDate);
                lotNumber = lotMeta.lotNumber();
                expiryDate = lotMeta.expiryDate();
                mfgDate = lotMeta.manufacturingDate();
            } else if (lotNumber != null) {
                LotMetadata lotMeta = resolveOptionalLotMetadata(itemOriginWarehouse, targetWarehouse, product, lotNumber, expiryDate, mfgDate);
                lotNumber = lotMeta.lotNumber();
                expiryDate = lotMeta.expiryDate();
                mfgDate = lotMeta.manufacturingDate();
            }

            BigDecimal unitPrice = invoiceItem.getUnitPrice() != null ? invoiceItem.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal lineAmount = unitPrice.multiply(returnQty).setScale(2, RoundingMode.HALF_UP);

            BigDecimal prevForThisLine = runningReturnedTracker.getOrDefault(invoiceItem.getId(), BigDecimal.ZERO);
            runningReturnedTracker.put(invoiceItem.getId(), prevForThisLine.add(returnQty));

            DealerReturnItem returnItem = DealerReturnItem.builder()
                    .invoiceItem(invoiceItem)
                    .order(invoiceItem.getOrder())
                    .orderCode(invoiceItem.getOrderCode())
                    .orderItemId(invoiceItem.getOrderItemId())
                    .product(product)
                    .productCode(product.getCode())
                    .productName(product.getName())
                    .manageLot(manageLot)
                    .unit(invoiceItem.getUnit())
                    .conversionFactor(conversionFactor)
                    .baseUnit(baseUnit)
                    .deliveredQuantity(invoiceItem.getDeliveredQuantity())
                    .previouslyReturnedQuantity(prevForThisLine)
                    .returnQuantity(returnQty)
                    .baseReturnQuantity(baseReturnQty)
                    .unitPrice(unitPrice)
                    .totalAmount(lineAmount)
                    .returnReason(itemReason)
                    .defective(isDefective)
                    .itemCondition(isDefective ? "DEFECTIVE" : "GOOD")
                    .targetWarehouse(targetWarehouse)
                    .lotNumber(lotNumber)
                    .expiryDate(expiryDate)
                    .manufacturingDate(mfgDate)
                    .note(itemReq.getNote() != null ? itemReq.getNote().trim() : null)
                    .build();

            dealerReturn.addItem(returnItem);
            totalReturnQty = totalReturnQty.add(returnQty);
            totalReturnAmount = totalReturnAmount.add(lineAmount);
        }

        dealerReturn.setDefectiveWarehouse(resolvedHeaderDefectiveWarehouse);
        dealerReturn.setTotalReturnQuantity(totalReturnQty);
        dealerReturn.setTotalAmount(totalReturnAmount);

        DealerReturn saved = dealerReturnRepository.save(dealerReturn);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "DEALER_RETURN",
                    saved.getReturnCode(),
                    "CREATE_DEALER_RETURN",
                    null,
                    DealerReturnStatus.PENDING.name(),
                    "Lập phiếu trả hàng từ đại lý '" + dealer.getName() + "' theo hóa đơn " + invoice.getInvoiceCode()
                            + " (chờ Quản lý kho xác nhận nhận hàng mới cộng tồn)",
                    actor
            );
        }

        return mapToResponse(saved, grants);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. QUẢN LÝ KHO XÁC NHẬN NHẬN HÀNG TRẢ -> CỘNG TỒN KHO & TỒN LÔ (S8-01)
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public DealerReturnResponse confirmReturn(Long id, ConfirmDealerReturnRequest request, String username) {
        requireAnyPermission("WAREHOUSE_APPROVE");
        Set<String> grants = currentAuthorities();
        String actor = currentUsername(username);

        if (id == null) {
            throw new IllegalArgumentException("ID phiếu trả hàng không được để trống");
        }

        DealerReturn dealerReturn = dealerReturnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng với ID: " + id));

        requireWarehouseScopeForConfirmation(dealerReturn, grants);

        if (dealerReturn.getStatus() == DealerReturnStatus.CONFIRMED) {
            throw new IllegalStateException("Phiếu trả hàng '" + dealerReturn.getReturnCode() + "' đã được xác nhận nhập kho trước đó");
        }
        if (dealerReturn.getStatus() == DealerReturnStatus.CANCELLED) {
            throw new IllegalStateException("Không thể xác nhận phiếu trả hàng đã bị hủy");
        }

        // Khóa hóa đơn gốc để kiểm tra lại không vượt số lượng đã giao trừ phần đã xác nhận trả
        Invoice invoice = invoiceRepository.findByIdForUpdate(dealerReturn.getInvoice().getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn gốc của phiếu trả hàng"));
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Hóa đơn gốc đã bị hủy, không thể xác nhận phiếu trả hàng");
        }

        // Nếu Quản lý kho chỉ định kho hàng lỗi cụ thể khi xác nhận
        Warehouse overrideDefectiveWarehouse = null;
        if (request != null && request.getDefectiveWarehouseId() != null) {
            overrideDefectiveWarehouse = resolveDefectiveWarehouse(request.getDefectiveWarehouseId(), dealerReturn.getWarehouse());
            dealerReturn.setDefectiveWarehouse(overrideDefectiveWarehouse);
        }

        // Kiểm tra tổng số lượng xác nhận không vượt quá số lượng đã giao
        Map<Long, BigDecimal> currentVoucherQtyByInvoiceItem = new LinkedHashMap<>();
        for (DealerReturnItem item : dealerReturn.getItems()) {
            currentVoucherQtyByInvoiceItem.merge(item.getInvoiceItem().getId(), item.getReturnQuantity(), BigDecimal::add);
        }
        for (Map.Entry<Long, BigDecimal> entry : currentVoucherQtyByInvoiceItem.entrySet()) {
            Long invoiceItemId = entry.getKey();
            BigDecimal voucherQty = entry.getValue();
            InvoiceItem ii = invoice.getItems().stream()
                    .filter(x -> x.getId().equals(invoiceItemId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Không tìm thấy dòng hóa đơn gốc ID: " + invoiceItemId));
            BigDecimal alreadyConfirmed = dealerReturnItemRepository.sumReturnQuantityByInvoiceItemIdExcludingReturn(
                    invoiceItemId, dealerReturn.getId(), List.of(DealerReturnStatus.CONFIRMED));
            if (alreadyConfirmed == null) {
                alreadyConfirmed = BigDecimal.ZERO;
            }
            BigDecimal maxAllowed = ii.getDeliveredQuantity().subtract(alreadyConfirmed);
            if (voucherQty.compareTo(maxAllowed) > 0) {
                throw new IllegalArgumentException("Số lượng trả vượt quá số lượng còn được trả trên hóa đơn gốc cho sản phẩm '" + ii.getProductCode() + "'");
            }
        }

        // Sắp xếp các dòng theo warehouseId và productId để khóa theo thứ tự nhất quán, tránh deadlock
        List<DealerReturnItem> sortedItems = new ArrayList<>(dealerReturn.getItems());
        sortedItems.sort(Comparator
                .comparing((DealerReturnItem i) -> i.getTargetWarehouse().getId())
                .thenComparing(i -> i.getProduct().getId())
                .thenComparing(DealerReturnItem::getId));

        for (DealerReturnItem item : sortedItems) {
            Product product = item.getProduct();
            if (product == null || !Boolean.TRUE.equals(product.getActive())) {
                throw new IllegalArgumentException("Sản phẩm '" + item.getProductCode() + "' không còn hoạt động");
            }

            Warehouse targetWh = item.getTargetWarehouse();
            if (Boolean.TRUE.equals(item.getDefective())) {
                if (overrideDefectiveWarehouse != null) {
                    targetWh = overrideDefectiveWarehouse;
                    item.setTargetWarehouse(targetWh);
                }
                if (targetWh == null || !targetWh.isDefectiveWarehouse()) {
                    throw new IllegalArgumentException("Hàng hỏng '" + item.getProductCode() + "' bắt buộc phải nhập vào kho hàng lỗi");
                }
            } else {
                if (targetWh == null || targetWh.isDefectiveWarehouse()) {
                    throw new IllegalArgumentException("Hàng tốt '" + item.getProductCode() + "' không được nhập vào kho hàng lỗi");
                }
            }

            Warehouse lockedWarehouse = warehouseRepository.findByIdForUpdate(targetWh.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho nhận hàng: " + targetWh.getCode()));
            if (!Boolean.TRUE.equals(lockedWarehouse.getActive())) {
                throw new IllegalArgumentException("Kho nhận hàng '" + lockedWarehouse.getName() + "' đã ngừng hoạt động");
            }

            BigDecimal baseReturnQty = item.getBaseReturnQuantity();

            // 1. Cộng tồn kho thực tế (WarehouseInventory) tại kho đích (kho thương mại hoặc kho hàng lỗi)
            WarehouseInventory inventory = inventoryRepository
                    .findByWarehouseIdAndProductIdForUpdate(lockedWarehouse.getId(), product.getId())
                    .orElseGet(() -> WarehouseInventory.builder()
                            .warehouse(lockedWarehouse)
                            .product(product)
                            .baseQuantity(BigDecimal.ZERO)
                            .reservedQuantity(BigDecimal.ZERO)
                            .inTransitQuantity(BigDecimal.ZERO)
                            .baseUnit(item.getBaseUnit() != null ? item.getBaseUnit() : product.getUnit())
                            .build());

            BigDecimal quantityBefore = inventory.getBaseQuantity() != null ? inventory.getBaseQuantity() : BigDecimal.ZERO;
            inventory.addBaseQuantity(baseReturnQty);
            inventoryRepository.save(inventory);

            // 2. Nếu SKU có quản lý lô (hoặc dòng trả có số lô): nhập lại đúng lô trong WarehouseLotInventory
            if (Boolean.TRUE.equals(product.getManageLot()) || (item.getLotNumber() != null && !item.getLotNumber().isBlank())) {
                String lotNumber = item.getLotNumber();
                if (lotNumber == null || lotNumber.isBlank()) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + "' có quản lý lô, bắt buộc phải có số lô khi xác nhận nhập kho");
                }
                WarehouseLotInventory lotInv = lotInventoryRepository
                        .findByWarehouseIdAndProductIdAndLotNumber(lockedWarehouse.getId(), product.getId(), lotNumber)
                        .orElseGet(() -> WarehouseLotInventory.builder()
                                .warehouse(lockedWarehouse)
                                .product(product)
                                .lotNumber(lotNumber)
                                .expiryDate(item.getExpiryDate())
                                .manufacturingDate(item.getManufacturingDate())
                                .baseQuantity(BigDecimal.ZERO)
                                .baseUnit(item.getBaseUnit() != null ? item.getBaseUnit() : product.getUnit())
                                .build());

                if (lotInv.getExpiryDate() == null && item.getExpiryDate() != null) {
                    lotInv.setExpiryDate(item.getExpiryDate());
                }
                if (lotInv.getManufacturingDate() == null && item.getManufacturingDate() != null) {
                    lotInv.setManufacturingDate(item.getManufacturingDate());
                }
                lotInv.addBaseQuantity(baseReturnQty);
                lotInventoryRepository.save(lotInv);
            }

            if (auditLogService != null) {
                auditLogService.logAction(
                        "INVENTORY",
                        lockedWarehouse.getCode() + ":" + product.getCode(),
                        "DEALER_RETURN_CONFIRM",
                        quantityBefore.toPlainString() + " " + product.getUnit(),
                        inventory.getBaseQuantity().toPlainString() + " " + product.getUnit(),
                        "Xác nhận nhận hàng trả từ đại lý theo phiếu " + dealerReturn.getReturnCode()
                                + (item.getLotNumber() != null ? " (Lô: " + item.getLotNumber() + ")" : "")
                                + (Boolean.TRUE.equals(item.getDefective()) ? " [Kho hàng lỗi]" : ""),
                        actor
                );
            }
        }

        LocalDateTime now = LocalDateTime.now();
        dealerReturn.setStatus(DealerReturnStatus.CONFIRMED);
        dealerReturn.setConfirmedBy(actor);
        dealerReturn.setConfirmedAt(now);
        dealerReturn.setUpdatedAt(now);
        if (request != null && request.getNote() != null && !request.getNote().isBlank()) {
            dealerReturn.setConfirmNote(request.getNote().trim());
        }

        DealerReturn saved = dealerReturnRepository.save(dealerReturn);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "DEALER_RETURN",
                    saved.getReturnCode(),
                    "CONFIRM_DEALER_RETURN",
                    DealerReturnStatus.PENDING.name(),
                    DealerReturnStatus.CONFIRMED.name(),
                    "Quản lý kho xác nhận nhận hàng trả và cộng tồn kho cho phiếu " + saved.getReturnCode(),
                    actor
            );
        }

        return mapToResponse(saved, grants);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. HỦY PHIẾU TRẢ HÀNG (KHI CHƯA XÁC NHẬN NHẬP KHO)
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public DealerReturnResponse cancelReturn(Long id, CancelDealerReturnRequest request, String username) {
        requireAnyPermission("ORDER_CREATE", "ORDER_CANCEL", "WAREHOUSE_APPROVE");
        Set<String> grants = currentAuthorities();
        String actor = currentUsername(username);

        if (id == null) {
            throw new IllegalArgumentException("ID phiếu trả hàng không được để trống");
        }
        String cancelReason = request != null ? request.getEffectiveReason() : null;
        if (cancelReason == null || cancelReason.isBlank()) {
            throw new IllegalArgumentException("Bắt buộc nhập lý do hủy phiếu trả hàng");
        }

        DealerReturn dealerReturn = dealerReturnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng với ID: " + id));

        checkSalesScopeForInvoice(dealerReturn.getInvoice(), grants, actor);

        if (dealerReturn.getStatus() == DealerReturnStatus.CONFIRMED) {
            throw new IllegalStateException("Phiếu trả hàng đã được Quản lý kho xác nhận nhập kho, không thể hủy");
        }
        if (dealerReturn.getStatus() == DealerReturnStatus.CANCELLED) {
            throw new IllegalStateException("Phiếu trả hàng này đã bị hủy trước đó");
        }

        LocalDateTime now = LocalDateTime.now();
        dealerReturn.setStatus(DealerReturnStatus.CANCELLED);
        dealerReturn.setCancelledBy(actor);
        dealerReturn.setCancelledAt(now);
        dealerReturn.setCancelReason(cancelReason);
        dealerReturn.setUpdatedAt(now);

        DealerReturn saved = dealerReturnRepository.save(dealerReturn);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "DEALER_RETURN",
                    saved.getReturnCode(),
                    "CANCEL_DEALER_RETURN",
                    DealerReturnStatus.PENDING.name(),
                    DealerReturnStatus.CANCELLED.name(),
                    "Hủy phiếu trả hàng: " + cancelReason,
                    actor
            );
        }

        return mapToResponse(saved, grants);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6. TÌM KIẾM & XEM CHI TIẾT PHIẾU TRẢ HÀNG
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Page<DealerReturnResponse> search(
            Long dealerId,
            Long invoiceId,
            Long warehouseId,
            DealerReturnStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            String keyword,
            int page,
            int size) {
        requireAnyPermission("ORDER_VIEW", "ORDER_CREATE", "INVOICE_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_APPROVE", "WAREHOUSE_IMPORT");
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("Trang phải không âm; số dòng mỗi trang từ 1 đến 200");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("Từ ngày không được sau đến ngày");
        }

        Set<String> grants = currentAuthorities();

        Specification<DealerReturn> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (dealerId != null) {
                predicates.add(cb.equal(root.get("dealer").get("id"), dealerId));
            }
            if (invoiceId != null) {
                predicates.add(cb.equal(root.get("invoice").get("id"), invoiceId));
            }
            if (warehouseId != null) {
                predicates.add(cb.or(
                        cb.equal(root.get("warehouse").get("id"), warehouseId),
                        cb.equal(root.get("defectiveWarehouse").get("id"), warehouseId)
                ));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate.atStartOfDay()));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), toDate.atTime(LocalTime.MAX)));
            }
            if (isSalesScopedOnly(grants)) {
                User currentUser = getCurrentUser();
                if (currentUser != null) {
                    predicates.add(cb.or(
                            cb.equal(root.get("dealer").get("assignedUser").get("id"), currentUser.getId()),
                            cb.equal(cb.lower(root.get("createdBy")), currentUser.getUsername().toLowerCase(Locale.ROOT))
                    ));
                } else {
                    predicates.add(cb.disjunction());
                }
            } else if (isWarehouseScopedOnly(grants)) {
                Set<Long> assignedWhIds = getAssignedWarehouseIds();
                if (assignedWhIds.isEmpty()) {
                    predicates.add(cb.disjunction());
                } else {
                    predicates.add(cb.or(
                            root.get("warehouse").get("id").in(assignedWhIds),
                            root.get("defectiveWarehouse").get("id").in(assignedWhIds)
                    ));
                }
            }
            if (keyword != null && !keyword.isBlank()) {
                String term = "%" + keyword.trim().toLowerCase(Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("returnCode")), term, '!'),
                        cb.like(cb.lower(root.get("invoiceCode")), term, '!'),
                        cb.like(cb.lower(root.get("dealerName")), term, '!'),
                        cb.like(cb.lower(root.get("dealerCode")), term, '!')
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return dealerReturnRepository.findAll(spec, pageable).map(r -> mapToResponse(r, grants));
    }

    @Override
    public DealerReturnResponse getById(Long id) {
        requireAnyPermission("ORDER_VIEW", "ORDER_CREATE", "INVOICE_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_APPROVE", "WAREHOUSE_IMPORT");
        if (id == null) {
            throw new IllegalArgumentException("ID phiếu trả hàng không được để trống");
        }
        Set<String> grants = currentAuthorities();
        DealerReturn dealerReturn = dealerReturnRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu trả hàng với ID: " + id));

        checkSalesScopeForInvoice(dealerReturn.getInvoice(), grants, currentUsername(null));
        if (isWarehouseScopedOnly(grants)) {
            requireWarehouseScopeForConfirmation(dealerReturn, grants);
        }

        return mapToResponse(dealerReturn, grants);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private record ResolvedLineInput(
            InvoiceItem invoiceItem,
            CreateDealerReturnItemRequest itemReq,
            BigDecimal returnQuantity,
            String returnReason
    ) {}

    private record LotMetadata(
            String lotNumber,
            LocalDate expiryDate,
            LocalDate manufacturingDate
    ) {}

    private InvoiceItem resolveInvoiceItem(Invoice invoice, CreateDealerReturnItemRequest itemReq, int lineNumber) {
        List<InvoiceItem> invoiceItems = invoice.getItems() != null ? invoice.getItems() : List.of();
        if (invoiceItems.isEmpty()) {
            throw new IllegalArgumentException("Hóa đơn gốc không có dòng hàng nào");
        }

        if (itemReq.getInvoiceItemId() != null) {
            return invoiceItems.stream()
                    .filter(ii -> ii.getId().equals(itemReq.getInvoiceItemId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Dòng hàng hóa đơn ID " + itemReq.getInvoiceItemId() + " không thuộc hóa đơn gốc '" + invoice.getInvoiceCode() + "'"));
        }

        if (itemReq.getOrderItemId() != null) {
            return invoiceItems.stream()
                    .filter(ii -> Objects.equals(ii.getOrderItemId(), itemReq.getOrderItemId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Dòng đơn hàng ID " + itemReq.getOrderItemId() + " không thuộc hóa đơn gốc '" + invoice.getInvoiceCode() + "'"));
        }

        if (itemReq.getProductId() != null) {
            List<InvoiceItem> matches = invoiceItems.stream()
                    .filter(ii -> ii.getProduct() != null && Objects.equals(ii.getProduct().getId(), itemReq.getProductId()))
                    .toList();
            if (matches.isEmpty()) {
                throw new IllegalArgumentException(
                        "Sản phẩm ID " + itemReq.getProductId() + " không tồn tại trên hóa đơn gốc '" + invoice.getInvoiceCode() + "'");
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException(
                        "Sản phẩm xuất hiện trên nhiều dòng của hóa đơn gốc, vui lòng chỉ định rõ invoiceItemId");
            }
            return matches.get(0);
        }

        throw new IllegalArgumentException("Dòng trả thứ " + lineNumber + " bắt buộc phải chọn dòng hàng từ hóa đơn gốc (invoiceItemId)");
    }

    private Warehouse resolveNormalWarehouse(Invoice invoice, Long requestedWarehouseId) {
        if (requestedWarehouseId != null) {
            Warehouse wh = warehouseRepository.findById(requestedWarehouseId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + requestedWarehouseId));
            if (!Boolean.TRUE.equals(wh.getActive())) {
                throw new IllegalArgumentException("Kho '" + wh.getName() + "' đã ngừng hoạt động");
            }
            return wh;
        }
        if (invoice.getItems() != null) {
            for (InvoiceItem item : invoice.getItems()) {
                if (item.getOrder() != null && item.getOrder().getWarehouse() != null) {
                    Warehouse orderWh = item.getOrder().getWarehouse();
                    if (Boolean.TRUE.equals(orderWh.getActive())) {
                        return orderWh;
                    }
                }
            }
        }
        if (invoice.getOrders() != null) {
            for (Order order : invoice.getOrders()) {
                if (order.getWarehouse() != null && Boolean.TRUE.equals(order.getWarehouse().getActive())) {
                    return order.getWarehouse();
                }
            }
        }
        if (invoice.getDealer() != null && invoice.getDealer().getWarehouse() != null) {
            Warehouse dealerWh = invoice.getDealer().getWarehouse();
            if (Boolean.TRUE.equals(dealerWh.getActive())) {
                return dealerWh;
            }
        }
        return warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .filter(w -> !w.isDefectiveWarehouse())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho thương mại đang hoạt động để nhận hàng trả"));
    }

    private boolean resolveIsDefective(CreateDealerReturnItemRequest itemReq, String itemReason) {
        if (itemReq.getDefective() != null) {
            return itemReq.getDefective();
        }
        if (itemReq.getDamaged() != null) {
            return itemReq.getDamaged();
        }
        if (itemReq.getCondition() != null && !itemReq.getCondition().isBlank()) {
            String cond = itemReq.getCondition().trim().toUpperCase(Locale.ROOT);
            if (Set.of("DEFECTIVE", "DAMAGED", "BROKEN", "HANG_HONG", "HANG_LOI", "LOI", "HONG").contains(cond)) {
                return true;
            }
            if (Set.of("GOOD", "NORMAL", "TOT", "BINH_THUONG").contains(cond)) {
                return false;
            }
        }
        if (itemReason != null && !itemReason.isBlank()) {
            String upper = itemReason.trim().toUpperCase(Locale.ROOT);
            if (DEFECTIVE_REASON_CODES.contains(upper)) {
                return true;
            }
            String lower = itemReason.trim().toLowerCase(Locale.ROOT);
            return lower.contains("hàng hỏng")
                    || lower.contains("hàng lỗi")
                    || lower.contains("hư hỏng")
                    || lower.contains("bể vỡ")
                    || lower.contains("lỗi nsx")
                    || lower.contains("damaged")
                    || lower.contains("defective");
        }
        return false;
    }

    private Warehouse resolveDefectiveWarehouse(Long requestedDefectiveWarehouseId, Warehouse normalWarehouse) {
        if (requestedDefectiveWarehouseId != null) {
            Warehouse wh = warehouseRepository.findById(requestedDefectiveWarehouseId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho hàng lỗi với ID: " + requestedDefectiveWarehouseId));
            if (!Boolean.TRUE.equals(wh.getActive())) {
                throw new IllegalArgumentException("Kho hàng lỗi '" + wh.getName() + "' đã ngừng hoạt động");
            }
            if (!wh.isDefectiveWarehouse()) {
                throw new IllegalArgumentException(
                        "Hàng hỏng bắt buộc phải nhập vào kho hàng lỗi, không được nhập vào kho thương mại '" + wh.getName() + "'");
            }
            return wh;
        }

        // Tìm kho hàng lỗi đang hoạt động trong hệ thống
        Optional<Warehouse> existingDefective = warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .filter(Warehouse::isDefectiveWarehouse)
                .findFirst();
        if (existingDefective.isPresent()) {
            return existingDefective.get();
        }

        // Tự động khởi tạo Kho hàng lỗi mặc định nếu hệ thống chưa khai báo kho hàng lỗi
        Warehouse created = Warehouse.builder()
                .code("KHO-HANG-LOI")
                .name("Kho hàng lỗi")
                .address(normalWarehouse != null ? normalWarehouse.getAddress() : "Khu vực biệt trữ hàng lỗi")
                .manager(normalWarehouse != null ? normalWarehouse.getManager() : null)
                .managerName(normalWarehouse != null ? normalWarehouse.getManagerName() : null)
                .active(true)
                .defective(true)
                .build();
        return warehouseRepository.save(created);
    }

    private LotMetadata validateAndResolveLotMetadata(
            Warehouse originWarehouse,
            Warehouse targetWarehouse,
            Product product,
            String requestedLotNumber,
            LocalDate requestedExpiry,
            LocalDate requestedMfgDate) {

        String cleanLot = requestedLotNumber.trim();
        List<WarehouseLotInventory> originLots = originWarehouse != null
                ? lotInventoryRepository.findByWarehouseIdAndProductId(originWarehouse.getId(), product.getId())
                : List.of();

        Optional<WarehouseLotInventory> matchedOriginLot = originLots.stream()
                .filter(l -> l.getLotNumber() != null && l.getLotNumber().equalsIgnoreCase(cleanLot))
                .findFirst();

        if (matchedOriginLot.isEmpty() && targetWarehouse != null) {
            matchedOriginLot = lotInventoryRepository
                    .findByWarehouseIdAndProductIdAndLotNumber(targetWarehouse.getId(), product.getId(), cleanLot);
        }

        if (!originLots.isEmpty() && matchedOriginLot.isEmpty()) {
            throw new IllegalArgumentException("Số lô '" + cleanLot + "' không đúng với lô đã quản lý/xuất của sản phẩm '"
                    + product.getCode() + "' tại kho '" + originWarehouse.getName() + "'");
        }

        if (matchedOriginLot.isPresent()) {
            WarehouseLotInventory existingLot = matchedOriginLot.get();
            if (requestedExpiry != null && existingLot.getExpiryDate() != null
                    && !requestedExpiry.equals(existingLot.getExpiryDate())) {
                throw new IllegalArgumentException("Hạn sử dụng không khớp với lô '" + existingLot.getLotNumber()
                        + "' (HSD đúng: " + existingLot.getExpiryDate() + ")");
            }
            if (requestedMfgDate != null && existingLot.getManufacturingDate() != null
                    && !requestedMfgDate.equals(existingLot.getManufacturingDate())) {
                throw new IllegalArgumentException("Ngày sản xuất không khớp với lô '" + existingLot.getLotNumber() + "'");
            }
            return new LotMetadata(
                    existingLot.getLotNumber(),
                    existingLot.getExpiryDate() != null ? existingLot.getExpiryDate() : requestedExpiry,
                    existingLot.getManufacturingDate() != null ? existingLot.getManufacturingDate() : requestedMfgDate
            );
        }

        LocalDate resolvedExpiry = requestedExpiry != null ? requestedExpiry : LocalDate.now().plusMonths(6);
        if (requestedMfgDate != null && resolvedExpiry.isBefore(requestedMfgDate)) {
            throw new IllegalArgumentException("Hạn sử dụng không được trước ngày sản xuất cho lô '" + cleanLot + "'");
        }
        return new LotMetadata(cleanLot, resolvedExpiry, requestedMfgDate);
    }

    private LotMetadata resolveOptionalLotMetadata(
            Warehouse originWarehouse,
            Warehouse targetWarehouse,
            Product product,
            String requestedLotNumber,
            LocalDate requestedExpiry,
            LocalDate requestedMfgDate) {
        String cleanLot = requestedLotNumber.trim();
        Optional<WarehouseLotInventory> existing = originWarehouse != null
                ? lotInventoryRepository.findByWarehouseIdAndProductIdAndLotNumber(originWarehouse.getId(), product.getId(), cleanLot)
                : Optional.empty();
        if (existing.isEmpty() && targetWarehouse != null) {
            existing = lotInventoryRepository.findByWarehouseIdAndProductIdAndLotNumber(targetWarehouse.getId(), product.getId(), cleanLot);
        }
        if (existing.isPresent()) {
            WarehouseLotInventory lot = existing.get();
            return new LotMetadata(
                    lot.getLotNumber(),
                    lot.getExpiryDate() != null ? lot.getExpiryDate() : requestedExpiry,
                    lot.getManufacturingDate() != null ? lot.getManufacturingDate() : requestedMfgDate
            );
        }
        return new LotMetadata(cleanLot, requestedExpiry, requestedMfgDate);
    }

    private String normalizeReturnCode(String rawCode) {
        if (rawCode != null && !rawCode.isBlank()) {
            String trimmed = rawCode.trim().toUpperCase(Locale.ROOT);
            if (trimmed.length() > 50) {
                throw new IllegalArgumentException("Mã phiếu trả hàng không được vượt quá 50 ký tự");
            }
            return trimmed;
        }
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        for (int i = 0; i < 10; i++) {
            int rand = ThreadLocalRandom.current().nextInt(1000, 9999);
            String candidate = "PTH-" + datePart + "-" + rand;
            if (!dealerReturnRepository.existsByReturnCode(candidate)) {
                return candidate;
            }
        }
        return "PTH-" + datePart + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private EligibleReturnInvoiceResponse mapToEligibleInvoiceResponse(Invoice invoice) {
        Warehouse normalWarehouse = null;
        try {
            normalWarehouse = resolveNormalWarehouse(invoice, null);
        } catch (Exception ignored) {
        }

        List<EligibleReturnInvoiceItemResponse> itemResponses = new ArrayList<>();
        BigDecimal totalDelivered = BigDecimal.ZERO;
        BigDecimal totalReturned = BigDecimal.ZERO;
        BigDecimal totalReturnable = BigDecimal.ZERO;

        if (invoice.getItems() != null) {
            for (InvoiceItem ii : invoice.getItems()) {
                Product product = ii.getProduct();
                BigDecimal delivered = ii.getDeliveredQuantity() != null ? ii.getDeliveredQuantity() : BigDecimal.ZERO;
                BigDecimal activeReturned = dealerReturnItemRepository.sumReturnQuantityByInvoiceItemIdAndStatuses(
                        ii.getId(), ACTIVE_RETURN_STATUSES);
                if (activeReturned == null) activeReturned = BigDecimal.ZERO;

                BigDecimal confirmedReturned = dealerReturnItemRepository.sumReturnQuantityByInvoiceItemIdAndStatuses(
                        ii.getId(), List.of(DealerReturnStatus.CONFIRMED));
                if (confirmedReturned == null) confirmedReturned = BigDecimal.ZERO;

                BigDecimal pendingReturned = activeReturned.subtract(confirmedReturned).max(BigDecimal.ZERO);
                BigDecimal returnable = delivered.subtract(activeReturned).max(BigDecimal.ZERO);

                totalDelivered = totalDelivered.add(delivered);
                totalReturned = totalReturned.add(activeReturned);
                totalReturnable = totalReturnable.add(returnable);

                OrderItem orderItem = ii.getOrderItemId() != null
                        ? orderItemRepository.findById(ii.getOrderItemId()).orElse(null)
                        : null;
                BigDecimal conversionFactor = (orderItem != null && orderItem.getConversionFactor() != null)
                        ? orderItem.getConversionFactor()
                        : (product != null ? unitConversionService.getConversionFactor(product, ii.getUnit()) : BigDecimal.ONE);
                String baseUnit = (orderItem != null && orderItem.getBaseUnit() != null)
                        ? orderItem.getBaseUnit()
                        : (product != null ? product.getUnit() : ii.getUnit());

                Warehouse lineWh = (ii.getOrder() != null && ii.getOrder().getWarehouse() != null)
                        ? ii.getOrder().getWarehouse()
                        : normalWarehouse;

                List<EligibleReturnInvoiceItemResponse.LotOption> lotOptions = List.of();
                if (product != null && lineWh != null) {
                    lotOptions = lotInventoryRepository.findByWarehouseIdAndProductId(lineWh.getId(), product.getId())
                            .stream()
                            .map(l -> EligibleReturnInvoiceItemResponse.LotOption.builder()
                                    .lotNumber(l.getLotNumber())
                                    .expiryDate(l.getExpiryDate())
                                    .manufacturingDate(l.getManufacturingDate())
                                    .currentBaseQuantity(l.getBaseQuantity())
                                    .baseUnit(l.getBaseUnit())
                                    .build())
                            .toList();
                }

                itemResponses.add(EligibleReturnInvoiceItemResponse.builder()
                        .invoiceItemId(ii.getId())
                        .orderId(ii.getOrder() != null ? ii.getOrder().getId() : null)
                        .orderCode(ii.getOrderCode())
                        .orderItemId(ii.getOrderItemId())
                        .productId(product != null ? product.getId() : null)
                        .productCode(ii.getProductCode())
                        .productName(ii.getProductName())
                        .manageLot(product != null && Boolean.TRUE.equals(product.getManageLot()))
                        .unit(ii.getUnit())
                        .conversionFactor(conversionFactor)
                        .baseUnit(baseUnit)
                        .unitPrice(ii.getUnitPrice())
                        .deliveredQuantity(delivered)
                        .previouslyReturnedQuantity(activeReturned)
                        .confirmedReturnedQuantity(confirmedReturned)
                        .pendingReturnedQuantity(pendingReturned)
                        .returnableQuantity(returnable)
                        .availableLots(lotOptions)
                        .build());
            }
        }

        return EligibleReturnInvoiceResponse.builder()
                .invoiceId(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .invoiceCode(invoice.getInvoiceCode())
                .invoiceDate(invoice.getInvoiceDate())
                .status(invoice.getStatus())
                .dealerId(invoice.getDealer() != null ? invoice.getDealer().getId() : null)
                .dealerCode(invoice.getDealerCode())
                .dealerName(invoice.getDealerName())
                .warehouseId(normalWarehouse != null ? normalWarehouse.getId() : null)
                .warehouseCode(normalWarehouse != null ? normalWarehouse.getCode() : null)
                .warehouseName(normalWarehouse != null ? normalWarehouse.getName() : null)
                .totalAmount(invoice.getTotalAmount())
                .totalDeliveredQuantity(totalDelivered)
                .totalReturnedQuantity(totalReturned)
                .totalReturnableQuantity(totalReturnable)
                .items(itemResponses)
                .build();
    }

    private DealerReturnResponse mapToResponse(DealerReturn r, Set<String> grants) {
        List<DealerReturnItemResponse> itemResponses = r.getItems() != null
                ? r.getItems().stream().map(this::mapToItemResponse).toList()
                : List.of();

        boolean isPending = r.getStatus() == DealerReturnStatus.PENDING;
        boolean canConfirm = isPending && hasAnyPermission(grants, "WAREHOUSE_APPROVE");
        boolean canCancel = isPending && hasAnyPermission(grants, "ORDER_CREATE", "ORDER_CANCEL", "WAREHOUSE_APPROVE");

        return DealerReturnResponse.builder()
                .id(r.getId())
                .version(r.getVersion())
                .returnCode(r.getReturnCode())
                .invoiceId(r.getInvoice() != null ? r.getInvoice().getId() : null)
                .invoiceCode(r.getInvoiceCode())
                .invoiceNumber(r.getInvoice() != null ? r.getInvoice().getInvoiceNumber() : null)
                .dealerId(r.getDealer() != null ? r.getDealer().getId() : null)
                .dealerCode(r.getDealerCode())
                .dealerName(r.getDealerName())
                .warehouseId(r.getWarehouse() != null ? r.getWarehouse().getId() : null)
                .warehouseCode(r.getWarehouse() != null ? r.getWarehouse().getCode() : null)
                .warehouseName(r.getWarehouse() != null ? r.getWarehouse().getName() : null)
                .defectiveWarehouseId(r.getDefectiveWarehouse() != null ? r.getDefectiveWarehouse().getId() : null)
                .defectiveWarehouseCode(r.getDefectiveWarehouse() != null ? r.getDefectiveWarehouse().getCode() : null)
                .defectiveWarehouseName(r.getDefectiveWarehouse() != null ? r.getDefectiveWarehouse().getName() : null)
                .status(r.getStatus())
                .statusDisplayName(r.getStatus() != null ? r.getStatus().getDisplayName() : null)
                .inventoryAdded(r.getStatus() == DealerReturnStatus.CONFIRMED)
                .returnReason(r.getReturnReason())
                .totalReturnQuantity(r.getTotalReturnQuantity())
                .totalAmount(r.getTotalAmount())
                .note(r.getNote())
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAt())
                .confirmedBy(r.getConfirmedBy())
                .confirmedAt(r.getConfirmedAt())
                .confirmNote(r.getConfirmNote())
                .cancelledBy(r.getCancelledBy())
                .cancelledAt(r.getCancelledAt())
                .cancelReason(r.getCancelReason())
                .updatedAt(r.getUpdatedAt())
                .canConfirm(canConfirm)
                .canCancel(canCancel)
                .items(itemResponses)
                .build();
    }

    private DealerReturnItemResponse mapToItemResponse(DealerReturnItem item) {
        Warehouse targetWh = item.getTargetWarehouse();
        return DealerReturnItemResponse.builder()
                .id(item.getId())
                .invoiceItemId(item.getInvoiceItem() != null ? item.getInvoiceItem().getId() : null)
                .orderId(item.getOrder() != null ? item.getOrder().getId() : null)
                .orderCode(item.getOrderCode())
                .orderItemId(item.getOrderItemId())
                .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                .productCode(item.getProductCode())
                .productName(item.getProductName())
                .manageLot(item.getManageLot())
                .unit(item.getUnit())
                .conversionFactor(item.getConversionFactor())
                .baseUnit(item.getBaseUnit())
                .deliveredQuantity(item.getDeliveredQuantity())
                .previouslyReturnedQuantity(item.getPreviouslyReturnedQuantity())
                .returnQuantity(item.getReturnQuantity())
                .baseReturnQuantity(item.getBaseReturnQuantity())
                .unitPrice(item.getUnitPrice())
                .totalAmount(item.getTotalAmount())
                .returnReason(item.getReturnReason())
                .defective(item.getDefective())
                .itemCondition(item.getItemCondition())
                .targetWarehouseId(targetWh != null ? targetWh.getId() : null)
                .targetWarehouseCode(targetWh != null ? targetWh.getCode() : null)
                .targetWarehouseName(targetWh != null ? targetWh.getName() : null)
                .targetWarehouseDefective(targetWh != null && targetWh.isDefectiveWarehouse())
                .lotNumber(item.getLotNumber())
                .expiryDate(item.getExpiryDate())
                .manufacturingDate(item.getManufacturingDate())
                .note(item.getNote())
                .build();
    }
}

