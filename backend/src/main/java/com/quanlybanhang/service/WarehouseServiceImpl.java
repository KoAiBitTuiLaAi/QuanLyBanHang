package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.ProductLocationRequest;
import com.quanlybanhang.dto.request.WarehouseLocationRequest;
import com.quanlybanhang.dto.request.WarehouseRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherItemRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.quanlybanhang.dto.request.BatchLotSuggestionRequest;
import com.quanlybanhang.dto.request.WarehouseTransferItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferReceiveItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferReceiveRequest;
import com.quanlybanhang.dto.request.WarehouseTransferRequest;
import com.quanlybanhang.dto.response.WarehouseTransferItemResponse;
import com.quanlybanhang.dto.response.WarehouseTransferResponse;
import com.quanlybanhang.model.WarehouseTransfer;
import com.quanlybanhang.model.WarehouseTransferItem;
import com.quanlybanhang.model.WarehouseTransferStatus;
import com.quanlybanhang.repository.WarehouseTransferRepository;
import java.util.stream.Collectors;
import java.time.temporal.ChronoUnit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

@Service
@RequiredArgsConstructor
public class WarehouseServiceImpl implements WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final WarehouseLocationRepository warehouseLocationRepository;
    private final WarehouseLocationProductRepository locationProductRepository;
    private final ProductRepository productRepository;
    private final UnitConversionRepository unitConversionRepository;
    private final UnitConversionService unitConversionService;
    private final WarehouseVoucherRepository voucherRepository;
    private final WarehouseInventoryRepository inventoryRepository;
    private final UserRepository userRepository;
    private final DealerRepository dealerRepository;
    private final OrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final WarehouseLotInventoryRepository lotInventoryRepository;
    private final WarehouseTransferRepository warehouseTransferRepository;
    private final AuditLogService auditLogService;
    private final WarehouseTransferService transferService;

    private void requireGrant(String... permissions) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_ADMIN") || Arrays.asList(permissions).contains(a.getAuthority()))) return;
        throw new AccessDeniedException("Bạn không có quyền thực hiện thao tác phiếu kho này");
    }

    private boolean mayAccessWarehouse(Long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        Set<String> grants = auth.getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.toSet());
        if (grants.contains("ROLE_ADMIN")) return true;
        if (userRepository == null) return true;
        User actor = userRepository.findByUsername(auth.getName()).orElseThrow(() -> new AccessDeniedException("Không tìm thấy tài khoản"));
        Set<Warehouse> assigned = actor.getWarehouses();
        if (assigned != null && !assigned.isEmpty()) return assigned.stream().anyMatch(w -> Objects.equals(w.getId(), id));
        return !grants.contains("ROLE_WAREHOUSE_STAFF") && !grants.contains("ROLE_WAREHOUSE_MANAGER");
    }

    private void requireWarehouse(Long id) {
        if (!mayAccessWarehouse(id)) throw new AccessDeniedException("Kho này không thuộc phạm vi được giao");
    }

    private Warehouse lockVoucherWarehouse(Warehouse warehouse) {
        requireWarehouse(warehouse.getId());
        Warehouse locked = warehouseRepository.findByIdForUpdate(warehouse.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho"));
        if (!Boolean.TRUE.equals(locked.getActive())) throw new IllegalArgumentException("Kho đã ngừng hoạt động");
        return locked;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getImportOptions() {
        requireGrant("WAREHOUSE_IMPORT", "WAREHOUSE_APPROVE", "WAREHOUSE_ADJUST");
        return Map.of("warehouses", warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream()
                        .filter(w -> mayAccessWarehouse(w.getId()))
                        .map(w -> new NamedResourceResponse(w.getId(), w.getCode(), w.getName())).toList(),
                "suppliers", supplierRepository.findAll().stream().filter(s -> Boolean.TRUE.equals(s.getActive()))
                        .sorted(Comparator.comparing(Supplier::getName))
                        .map(s -> new NamedResourceResponse(s.getId(), s.getCode(), s.getName())).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseVoucherResponse> getImports(Long warehouseId) {
        requireGrant("WAREHOUSE_IMPORT", "WAREHOUSE_APPROVE", "WAREHOUSE_ADJUST");
        if (warehouseId != null) requireWarehouse(warehouseId);
        List<WarehouseVoucher> rows = voucherRepository.searchVouchers(warehouseId, null, null, null);
        Set<Long> importIds = rows.stream().filter(v -> v.getType() == WarehouseVoucherType.IMPORT)
                .filter(v -> mayAccessWarehouse(v.getWarehouse().getId())).map(WarehouseVoucher::getId).collect(Collectors.toSet());
        return rows.stream()
                .filter(v -> importIds.contains(v.getId()) || (v.getType() == WarehouseVoucherType.ADJUSTMENT && importIds.contains(v.getReferenceVoucherId())))
                .filter(v -> mayAccessWarehouse(v.getWarehouse().getId())).map(this::mapToVoucherResponse).toList();
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. OPTIONS & DANH SÁCH NHANH
    // ═══════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<NamedResourceResponse> getWarehouseOptions() {
        return warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(warehouse -> new NamedResourceResponse(
                        warehouse.getId(), warehouse.getCode(), warehouse.getName()))
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. QUẢN LÝ KHO HÀNG (S5-03)
    // ═══════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseResponse> getAllWarehouses(String keyword, Boolean active) {
        List<Warehouse> list = warehouseRepository.findAllByOrderByCodeAsc();

        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim().toLowerCase() : null;

        return list.stream()
                .filter(w -> {
                    if (active != null && !active.equals(w.getActive())) {
                        return false;
                    }
                    if (kw != null) {
                        boolean matchCode = w.getCode() != null && w.getCode().toLowerCase().contains(kw);
                        boolean matchName = w.getName() != null && w.getName().toLowerCase().contains(kw);
                        boolean matchAddress = w.getAddress() != null && w.getAddress().toLowerCase().contains(kw);
                        boolean matchManager = (w.getManagerName() != null && w.getManagerName().toLowerCase().contains(kw))
                                || (w.getManager() != null && (
                                (w.getManager().getFullName() != null && w.getManager().getFullName().toLowerCase().contains(kw))
                                || (w.getManager().getUsername() != null && w.getManager().getUsername().toLowerCase().contains(kw))));
                        return matchCode || matchName || matchAddress || matchManager;
                    }
                    return true;
                })
                .map(this::mapToWarehouseResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseResponse getWarehouseById(Long id) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + id));

        WarehouseResponse response = mapToWarehouseResponse(warehouse);

        // Nạp danh sách vị trí lưu trực thuộc kho
        List<WarehouseLocation> locations = warehouseLocationRepository.findByWarehouseIdOrderByCodeAsc(id);
        response.setLocations(locations.stream().map(this::mapToLocationResponse).collect(Collectors.toList()));

        return response;
    }

    @Override
    @Transactional
    public WarehouseResponse createWarehouse(WarehouseRequest request, String username) {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu kho không được để trống");
        }
        if (request.getCode() == null || request.getCode().isBlank()) {
            throw new IllegalArgumentException("Mã kho không được để trống");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Tên kho không được để trống");
        }

        String code = request.getCode().trim().toUpperCase();
        if (warehouseRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Mã kho '" + code + "' đã tồn tại trong hệ thống");
        }

        User manager = null;
        if (request.getManagerUserId() != null) {
            manager = userRepository.findById(request.getManagerUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng phụ trách với ID: " + request.getManagerUserId()));
        }

        String managerName = request.getManagerName() != null && !request.getManagerName().isBlank()
                ? request.getManagerName().trim()
                : (manager != null ? manager.getFullName() : null);

        Warehouse warehouse = Warehouse.builder()
                .code(code)
                .name(request.getName().trim())
                .address(request.getAddress() != null ? request.getAddress().trim() : null)
                .manager(manager)
                .managerName(managerName)
                .active(request.getActive() != null ? request.getActive() : true)
                .defective(Boolean.TRUE.equals(request.getDefective()))
                .build();

        Warehouse saved = warehouseRepository.save(warehouse);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "WAREHOUSE",
                    saved.getId().toString(),
                    "CREATE_WAREHOUSE",
                    null,
                    "Tạo mới kho '" + saved.getName() + "' (" + saved.getCode() + ")",
                    username != null ? username : "system"
            );
        }

        return mapToWarehouseResponse(saved);
    }

    @Override
    @Transactional
    public WarehouseResponse updateWarehouse(Long id, WarehouseRequest request, String username) {
        if (id == null) {
            throw new IllegalArgumentException("ID kho không được để trống");
        }
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu cập nhật kho không được để trống");
        }

        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + id));

        if (request.getCode() != null && !request.getCode().isBlank()) {
            String newCode = request.getCode().trim().toUpperCase();
            if (!newCode.equalsIgnoreCase(warehouse.getCode())) {
                if (warehouseRepository.existsByCodeAndIdNot(newCode, id)) {
                    throw new IllegalArgumentException("Mã kho '" + newCode + "' đã được sử dụng bởi kho khác");
                }
                warehouse.setCode(newCode);
            }
        }

        if (request.getName() != null && !request.getName().isBlank()) {
            warehouse.setName(request.getName().trim());
        }

        if (request.getAddress() != null) {
            warehouse.setAddress(request.getAddress().trim());
        }

        if (request.getManagerUserId() != null) {
            if (request.getManagerUserId() <= 0) {
                warehouse.setManager(null);
            } else {
                User manager = userRepository.findById(request.getManagerUserId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng phụ trách với ID: " + request.getManagerUserId()));
                warehouse.setManager(manager);
            }
        }

        if (request.getManagerName() != null) {
            warehouse.setManagerName(request.getManagerName().trim());
        } else if (warehouse.getManager() != null && (warehouse.getManagerName() == null || warehouse.getManagerName().isBlank())) {
            warehouse.setManagerName(warehouse.getManager().getFullName());
        }

        if (request.getActive() != null) {
            warehouse.setActive(request.getActive());
        }
        if (request.getDefective() != null) {
            warehouse.setDefective(request.getDefective());
        }

        Warehouse saved = warehouseRepository.save(warehouse);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "WAREHOUSE",
                    saved.getId().toString(),
                    "UPDATE_WAREHOUSE",
                    null,
                    "Cập nhật thông tin kho '" + saved.getName() + "' (" + saved.getCode() + ")",
                    username != null ? username : "system"
            );
        }

        return mapToWarehouseResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<WarehouseDealerResponse> getDealerWarehouseAssignments(
            String keyword, org.springframework.data.domain.Pageable pageable) {
        String term = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return dealerRepository.searchDealers(term, null, null, null, null, null, pageable)
                .map(this::mapDealerWarehouse);
    }

    @Override
    @Transactional
    public WarehouseDealerResponse assignDealerWarehouse(Long dealerId, Long warehouseId, String username) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý"));
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho phục vụ"));
        if (!Boolean.TRUE.equals(warehouse.getActive())) {
            throw new IllegalArgumentException("Chỉ được gán kho đang hoạt động cho đại lý");
        }
        String before = dealer.getWarehouse() == null ? null : dealer.getWarehouse().getCode();
        dealer.setWarehouse(warehouse);
        Dealer saved = dealerRepository.save(dealer);
        auditLogService.logAction("DEALER", dealerId.toString(), "ASSIGN_WAREHOUSE",
                before, warehouse.getCode(), username);
        return mapDealerWarehouse(saved);
    }

    private WarehouseDealerResponse mapDealerWarehouse(Dealer dealer) {
        Warehouse warehouse = dealer.getWarehouse();
        return new WarehouseDealerResponse(dealer.getId(), dealer.getCode(), dealer.getName(), dealer.getActive(),
                warehouse == null ? null : warehouse.getId(), warehouse == null ? null : warehouse.getCode(),
                warehouse == null ? null : warehouse.getName());
    }

    @Override
    @Transactional
    public void deleteWarehouse(Long id, String username) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + id));

        // Kiểm tra ràng buộc phiếu kho
        List<WarehouseVoucher> vouchers = voucherRepository.findByWarehouseIdOrderByRecordedAtDesc(id);
        if (vouchers != null && !vouchers.isEmpty()) {
            // Có phiếu kho -> chỉ chuyển trạng thái ngừng hoạt động
            warehouse.setActive(false);
            warehouseRepository.save(warehouse);
            if (auditLogService != null) {
                auditLogService.logAction(
                        "WAREHOUSE",
                        warehouse.getId().toString(),
                        "DEACTIVATE_WAREHOUSE",
                        null,
                        "Ngừng hoạt động kho '" + warehouse.getName() + "' do đã phát sinh giao dịch/phiếu kho",
                        username != null ? username : "system"
                );
            }
            return;
        }

        // Kiểm tra vị trí kho
        long locCount = warehouseLocationRepository.countByWarehouseId(id);
        if (locCount > 0) {
            warehouse.setActive(false);
            warehouseRepository.save(warehouse);
            if (auditLogService != null) {
                auditLogService.logAction(
                        "WAREHOUSE",
                        warehouse.getId().toString(),
                        "DEACTIVATE_WAREHOUSE",
                        null,
                        "Ngừng hoạt động kho '" + warehouse.getName() + "' do đang có " + locCount + " vị trí lưu trực thuộc",
                        username != null ? username : "system"
                );
            }
            return;
        }

        warehouseRepository.delete(warehouse);
        if (auditLogService != null) {
            auditLogService.logAction(
                    "WAREHOUSE",
                    id.toString(),
                    "DELETE_WAREHOUSE",
                    null,
                    "Xóa kho '" + warehouse.getName() + "' (" + warehouse.getCode() + ")",
                    username != null ? username : "system"
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. QUẢN LÝ VỊ TRÍ LƯU TRONG KHO (S5-03: KỆ HOẶC KHU)
    // ═══════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseLocationResponse> getLocations(Long warehouseId, LocationType type, Long parentId, Boolean active, String keyword) {
        List<WarehouseLocation> locations;

        if (warehouseId != null) {
            locations = warehouseLocationRepository.findByWarehouseIdOrderByCodeAsc(warehouseId);
        } else {
            locations = warehouseLocationRepository.findAll();
        }

        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim().toLowerCase() : null;

        return locations.stream()
                .filter(loc -> {
                    if (type != null && loc.getLocationType() != type) {
                        return false;
                    }
                    if (parentId != null) {
                        if (loc.getParent() == null || !parentId.equals(loc.getParent().getId())) {
                            return false;
                        }
                    }
                    if (active != null && !active.equals(loc.getActive())) {
                        return false;
                    }
                    if (kw != null) {
                        boolean matchCode = loc.getCode() != null && loc.getCode().toLowerCase().contains(kw);
                        boolean matchName = loc.getName() != null && loc.getName().toLowerCase().contains(kw);
                        boolean matchDesc = loc.getDescription() != null && loc.getDescription().toLowerCase().contains(kw);
                        return matchCode || matchName || matchDesc;
                    }
                    return true;
                })
                .map(this::mapToLocationResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseLocationResponse getLocationById(Long id) {
        WarehouseLocation location = warehouseLocationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí lưu kho với ID: " + id));

        WarehouseLocationResponse response = mapToLocationResponse(location);

        // Nạp danh sách sản phẩm đang lưu tại vị trí này
        List<WarehouseLocationProduct> products = locationProductRepository.findByLocationId(id);
        response.setProducts(products.stream().map(this::mapToProductLocationResponse).collect(Collectors.toList()));

        // Nạp danh sách kệ con nếu là Khu (ZONE)
        if (location.getLocationType() == LocationType.ZONE) {
            List<WarehouseLocation> childShelves = warehouseLocationRepository.findByParentIdOrderByCodeAsc(id);
            response.setChildShelves(childShelves.stream().map(this::mapToLocationResponse).collect(Collectors.toList()));
        }

        return response;
    }

    @Override
    @Transactional
    public WarehouseLocationResponse createLocation(Long warehouseId, WarehouseLocationRequest request, String username) {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu vị trí kho không được để trống");
        }

        Long targetWarehouseId = warehouseId != null ? warehouseId : request.getWarehouseId();
        if (targetWarehouseId == null) {
            throw new IllegalArgumentException("ID kho không được để trống");
        }

        // Serialize code allocation within a warehouse, including manually entered codes.
        // The existing (warehouse_id, code) unique constraint remains the final safeguard.
        Warehouse warehouse = warehouseRepository.findByIdForUpdate(targetWarehouseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + targetWarehouseId));

        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Tên vị trí không được để trống");
        }

        LocationType locationType = request.getLocationType() != null ? request.getLocationType() : LocationType.SHELF;
        String code = request.getCode() == null || request.getCode().isBlank()
                ? generateLocationCode(targetWarehouseId, locationType)
                : request.getCode().trim().toUpperCase(Locale.ROOT);
        if (warehouseLocationRepository.existsByWarehouseIdAndCode(targetWarehouseId, code)) {
            throw new IllegalArgumentException("Mã vị trí '" + code + "' đã tồn tại trong kho " + warehouse.getName());
        }

        WarehouseLocation parent = null;
        if (request.getParentLocationId() != null && request.getParentLocationId() > 0) {
            parent = warehouseLocationRepository.findById(request.getParentLocationId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí cha với ID: " + request.getParentLocationId()));

            if (!parent.getWarehouse().getId().equals(targetWarehouseId)) {
                throw new IllegalArgumentException("Vị trí cha phải thuộc cùng một kho (" + warehouse.getName() + ")");
            }
            if (parent.getLocationType() != LocationType.ZONE) {
                throw new IllegalArgumentException("Vị trí cha phải là Khu vực (ZONE)");
            }
            if (locationType == LocationType.ZONE) {
                throw new IllegalArgumentException("Khu vực không được nằm trong khu vực khác");
            }
        }

        WarehouseLocation location = WarehouseLocation.builder()
                .warehouse(warehouse)
                .code(code)
                .name(request.getName().trim())
                .locationType(locationType)
                .parent(parent)
                .description(request.getDescription() != null ? request.getDescription().trim() : null)
                .pickingSequence(request.getPickingSequence())
                .active(request.getActive() != null ? request.getActive() : true)
                .build();

        WarehouseLocation saved = warehouseLocationRepository.save(location);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "WAREHOUSE_LOCATION",
                    saved.getId().toString(),
                    "CREATE_LOCATION",
                    null,
                    "Khai báo vị trí " + saved.getLocationType().getDisplayName() + " '" + saved.getName() +
                            "' (" + saved.getCode() + ") trong kho " + warehouse.getName(),
                    username != null ? username : "system"
            );
        }

        return mapToLocationResponse(saved);
    }

    /** Caller holds the warehouse row lock until this transaction commits. */
    private String generateLocationCode(Long warehouseId, LocationType type) {
        String prefix = switch (type) {
            case ZONE -> "KHU-";
            case SHELF -> "KE-";
            default -> type.name() + "-";
        };
        java.math.BigInteger highest = java.math.BigInteger.ZERO;
        for (String existing : warehouseLocationRepository.findCodesByWarehouseId(warehouseId)) {
            if (existing == null || !existing.toUpperCase(Locale.ROOT).startsWith(prefix)) continue;
            String suffix = existing.substring(prefix.length());
            if (suffix.matches("[0-9]+")) highest = highest.max(new java.math.BigInteger(suffix));
        }
        String digits = highest.add(java.math.BigInteger.ONE).toString();
        String code = prefix + "0".repeat(Math.max(0, 3 - digits.length())) + digits;
        if (code.length() > 50) {
            throw new IllegalArgumentException("Không thể tự sinh mã vị trí trong giới hạn 50 ký tự; vui lòng nhập mã riêng");
        }
        return code;
    }

    @Override
    @Transactional
    public WarehouseLocationResponse updateLocation(Long id, WarehouseLocationRequest request, String username) {
        if (id == null) {
            throw new IllegalArgumentException("ID vị trí kho không được để trống");
        }
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu cập nhật vị trí không được để trống");
        }

        WarehouseLocation location = warehouseLocationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí kho với ID: " + id));

        if (request.getCode() != null && !request.getCode().isBlank()) {
            String newCode = request.getCode().trim().toUpperCase(Locale.ROOT);
            if (!newCode.equalsIgnoreCase(location.getCode())) {
                warehouseRepository.findByIdForUpdate(location.getWarehouse().getId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho của vị trí"));
                if (warehouseLocationRepository.existsByWarehouseIdAndCodeAndIdNot(location.getWarehouse().getId(), newCode, id)) {
                    throw new IllegalArgumentException("Mã vị trí '" + newCode + "' đã được sử dụng trong kho này");
                }
                location.setCode(newCode);
            }
        }

        if (request.getName() != null && !request.getName().isBlank()) {
            location.setName(request.getName().trim());
        }

        if (request.getLocationType() != null && request.getLocationType() != LocationType.ZONE
                && warehouseLocationRepository.countByParentId(id) > 0) {
            throw new IllegalArgumentException("Không thể đổi loại khu vực đang chứa vị trí trực thuộc");
        }
        if (request.getLocationType() != null) {
            location.setLocationType(request.getLocationType());
        }

        if (location.getLocationType() == LocationType.ZONE) {
            if (request.getParentLocationId() != null && request.getParentLocationId() > 0) {
                throw new IllegalArgumentException("Khu vực không được nằm trong khu vực khác");
            }
            location.setParent(null);
        } else if (request.getParentLocationId() != null && request.getParentLocationId() > 0) {
            if (request.getParentLocationId().equals(id)) {
                throw new IllegalArgumentException("Vị trí không thể là cha của chính nó");
            }
            WarehouseLocation parent = warehouseLocationRepository.findById(request.getParentLocationId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí cha với ID: " + request.getParentLocationId()));

            if (!parent.getWarehouse().getId().equals(location.getWarehouse().getId())) {
                throw new IllegalArgumentException("Vị trí cha phải thuộc cùng một kho");
            }
            if (parent.getLocationType() != LocationType.ZONE) {
                throw new IllegalArgumentException("Vị trí cha phải là Khu vực (ZONE)");
            }
            location.setParent(parent);
        } else if (request.getParentLocationId() != null && request.getParentLocationId() <= 0) {
            location.setParent(null);
        }

        if (request.getDescription() != null) {
            location.setDescription(request.getDescription().trim());
        }
        location.setPickingSequence(request.getPickingSequence());

        if (request.getActive() != null) {
            location.setActive(request.getActive());
        }

        WarehouseLocation saved = warehouseLocationRepository.save(location);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "WAREHOUSE_LOCATION",
                    saved.getId().toString(),
                    "UPDATE_LOCATION",
                    null,
                    "Cập nhật vị trí " + saved.getLocationType().getDisplayName() + " '" + saved.getName() + "' (" + saved.getCode() + ")",
                    username != null ? username : "system"
            );
        }

        return mapToLocationResponse(saved);
    }

    @Override
    @Transactional
    public void deleteLocation(Long id, String username) {
        WarehouseLocation location = warehouseLocationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí kho với ID: " + id));

        long childCount = warehouseLocationRepository.countByParentId(id);
        if (childCount > 0) {
            throw new IllegalArgumentException("Không thể xoá vì vị trí này đang chứa " + childCount + " kệ/khu trực thuộc");
        }

        long productCount = locationProductRepository.countByLocationId(id);
        if (productCount > 0) {
            throw new IllegalArgumentException("Không thể xoá vì vị trí này đang có " + productCount + " sản phẩm được gán lưu trữ");
        }

        warehouseLocationRepository.delete(location);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "WAREHOUSE_LOCATION",
                    id.toString(),
                    "DELETE_LOCATION",
                    null,
                    "Xóa vị trí " + location.getLocationType().getDisplayName() + " '" + location.getName() + "' (" + location.getCode() + ")",
                    username != null ? username : "system"
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. GÁN SẢN PHẨM VÀO VỊ TRÍ KỆ / KHU (S5-03)
    // ═══════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ProductLocationResponse assignProductToLocation(Long locationId, ProductLocationRequest request, String username) {
        if (locationId == null) {
            throw new IllegalArgumentException("ID vị trí không được để trống");
        }
        if (request == null || request.getProductId() == null) {
            throw new IllegalArgumentException("ID sản phẩm không được để trống");
        }

        WarehouseLocation location = warehouseLocationRepository.findById(locationId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí kho với ID: " + locationId));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + request.getProductId()));

        boolean isDefault = request.getIsDefault() != null ? request.getIsDefault() : true;

        // Nếu đặt làm vị trí mặc định trong kho, xóa cờ mặc định cũ của sản phẩm tại kho này
        if (isDefault) {
            List<WarehouseLocationProduct> existingWarehouseMappings =
                    locationProductRepository.findByWarehouseIdAndProductId(location.getWarehouse().getId(), product.getId());
            for (WarehouseLocationProduct m : existingWarehouseMappings) {
                if (Boolean.TRUE.equals(m.getIsDefault()) && !m.getLocation().getId().equals(locationId)) {
                    m.setIsDefault(false);
                    locationProductRepository.save(m);
                }
            }
        }

        WarehouseLocationProduct mapping = locationProductRepository.findByLocationIdAndProductId(locationId, product.getId())
                .orElseGet(() -> WarehouseLocationProduct.builder()
                        .location(location)
                        .product(product)
                        .build());

        mapping.setIsDefault(isDefault);
        if (request.getNote() != null) {
            mapping.setNote(request.getNote().trim());
        }

        WarehouseLocationProduct saved = locationProductRepository.save(mapping);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "WAREHOUSE_LOCATION_PRODUCT",
                    saved.getId() != null ? saved.getId().toString() : "N/A",
                    "ASSIGN_PRODUCT_LOCATION",
                    null,
                    "Gán sản phẩm '" + product.getName() + "' (" + product.getCode() + ") vào vị trí '" +
                            location.getName() + "' (" + location.getCode() + ") trong kho " + location.getWarehouse().getName(),
                    username != null ? username : "system"
            );
        }

        return mapToProductLocationResponse(saved);
    }

    @Override
    @Transactional
    public void removeProductFromLocation(Long locationId, Long productId, String username) {
        if (locationId == null || productId == null) {
            throw new IllegalArgumentException("ID vị trí và ID sản phẩm không được để trống");
        }

        WarehouseLocationProduct mapping = locationProductRepository.findByLocationIdAndProductId(locationId, productId)
                .orElseThrow(() -> new IllegalArgumentException("Sản phẩm chưa được gán vào vị trí này"));

        locationProductRepository.delete(mapping);

        if (auditLogService != null) {
            auditLogService.logAction(
                    "WAREHOUSE_LOCATION_PRODUCT",
                    mapping.getId().toString(),
                    "REMOVE_PRODUCT_LOCATION",
                    null,
                    "Bỏ gán sản phẩm ID " + productId + " khỏi vị trí ID " + locationId,
                    username != null ? username : "system"
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductLocationResponse> getProductsByLocation(Long locationId) {
        return locationProductRepository.findByLocationId(locationId).stream()
                .map(this::mapToProductLocationResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductLocationResponse> getLocationsByProduct(Long productId, Long warehouseId) {
        List<WarehouseLocationProduct> list;
        if (warehouseId != null) {
            list = locationProductRepository.findByWarehouseIdAndProductId(warehouseId, productId);
        } else {
            list = locationProductRepository.findByProductId(productId);
        }
        return list.stream().map(this::mapToProductLocationResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductLocationResponse getProductLocationInWarehouse(Long warehouseId, Long productId) {
        if (warehouseId == null || productId == null) return null;
        List<WarehouseLocationProduct> list = locationProductRepository.findByWarehouseIdAndProductId(warehouseId, productId);
        if (list.isEmpty()) return null;
        return mapToProductLocationResponse(list.get(0));
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. SOẠN ĐƠN HÀNG KÈM VỊ TRÍ KỆ CHÍNH XÁC (S5-03)
    // ═══════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public OrderPickingListResponse getOrderPickingList(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("ID đơn hàng không được để trống");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + orderId));

        Warehouse warehouse = order.getWarehouse();
        if (warehouse == null && order.getDealer() != null) {
            warehouse = order.getDealer().getWarehouse();
        }

        List<OrderPickingItemResponse> itemResponses = new ArrayList<>();

        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                Product p = item.getProduct();
                if (p == null) continue;

                // Tìm vị trí kệ trong kho xuất hàng
                WarehouseLocation loc = null;
                if (warehouse != null) {
                    List<WarehouseLocationProduct> mappings =
                            locationProductRepository.findByWarehouseIdAndProductId(warehouse.getId(), p.getId());
                    if (!mappings.isEmpty()) {
                        loc = mappings.get(0).getLocation();
                    }
                }

                // Lấy tồn khả dụng tại kho
                BigDecimal availableQty = BigDecimal.ZERO;
                if (warehouse != null) {
                    Optional<WarehouseInventory> invOpt =
                            inventoryRepository.findByWarehouseIdAndProductId(warehouse.getId(), p.getId());
                    if (invOpt.isPresent()) {
                        availableQty = invOpt.get().getAvailableQuantity();
                    }
                }

                String zoneName = null;
                String shelfName = null;
                String locDisplay = "Chưa gán vị trí";

                if (loc != null) {
                    if (loc.getParent() != null) {
                        zoneName = loc.getParent().getName();
                        shelfName = loc.getName();
                        locDisplay = loc.getParent().getName() + " > " + loc.getName();
                    } else if (loc.getLocationType() == LocationType.ZONE) {
                        zoneName = loc.getName();
                        locDisplay = loc.getName();
                    } else {
                        shelfName = loc.getName();
                        locDisplay = loc.getName();
                    }
                }

                itemResponses.add(OrderPickingItemResponse.builder()
                        .orderItemId(item.getId())
                        .productId(p.getId())
                        .productCode(p.getCode())
                        .productName(p.getName())
                        .quantity(item.getQuantity())
                        .unit(item.getUnit())
                        .baseQuantity(item.getBaseQuantity())
                        .baseUnit(item.getBaseUnit())
                        .warehouseId(warehouse != null ? warehouse.getId() : null)
                        .warehouseCode(warehouse != null ? warehouse.getCode() : null)
                        .warehouseName(warehouse != null ? warehouse.getName() : null)
                        .locationId(loc != null ? loc.getId() : null)
                        .locationCode(loc != null ? loc.getCode() : null)
                        .locationName(loc != null ? loc.getName() : null)
                        .locationType(loc != null ? loc.getLocationType() : null)
                        .locationTypeDisplayName(loc != null && loc.getLocationType() != null ? loc.getLocationType().getDisplayName() : null)
                        .zoneName(zoneName)
                        .shelfName(shelfName)
                        .locationDisplay(locDisplay)
                        .availableQuantity(availableQty)
                        .build());
            }
        }

        return OrderPickingListResponse.builder()
                .orderId(order.getId())
                .orderCode(order.getOrderCode())
                .orderStatus(order.getStatus())
                .expectedDeliveryDate(order.getExpectedDeliveryDate())
                .dealerId(order.getDealer() != null ? order.getDealer().getId() : null)
                .dealerCode(order.getDealer() != null ? order.getDealer().getCode() : null)
                .dealerName(order.getDealer() != null ? order.getDealer().getName() : null)
                .warehouseId(warehouse != null ? warehouse.getId() : null)
                .warehouseCode(warehouse != null ? warehouse.getCode() : null)
                .warehouseName(warehouse != null ? warehouse.getName() : null)
                .warehouseAddress(warehouse != null ? warehouse.getAddress() : null)
                .items(itemResponses)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. NHẬP / XUẤT / ĐIỀU CHỈNH / TỒN KHO (S1-05, S2-07, S4-03)
    // ═══════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public WarehouseVoucherResponse importGoods(WarehouseVoucherRequest request, String username) {
        requireGrant("WAREHOUSE_IMPORT");
        if (request != null && !Boolean.TRUE.equals(request.getDraft()) && !"DRAFT".equalsIgnoreCase(request.getStatus())) {
            requireGrant("WAREHOUSE_APPROVE");
        }
        return recordVoucher(request, WarehouseVoucherType.IMPORT, username);
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse exportGoods(WarehouseVoucherRequest request, String username) {
        requireGrant("WAREHOUSE_EXPORT");
        return recordVoucher(request, WarehouseVoucherType.EXPORT, username);
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse adjustInventory(WarehouseVoucherRequest request, String username) {
        requireGrant("WAREHOUSE_ADJUST");
        return recordVoucher(request, WarehouseVoucherType.ADJUSTMENT, username);
    }

    @Transactional
    private boolean canOverrideExpiredLot() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        Set<String> grants = new HashSet<>();
        if (auth.getAuthorities() != null) {
            auth.getAuthorities().forEach(a -> grants.add(a.getAuthority()));
        }
        return grants.contains("ROLE_ADMIN")
                || grants.contains("WAREHOUSE_APPROVE")
                || grants.contains("WAREHOUSE_MANAGE");
    }

    @Transactional
    public WarehouseVoucherResponse recordVoucher(WarehouseVoucherRequest request, WarehouseVoucherType type, String username) {
        if (request == null) {
            throw new IllegalArgumentException("Không được để trống nội dung phiếu kho");
        }

        // Chuẩn hóa danh sách items
        List<WarehouseVoucherItemRequest> itemRequests = normalizeItemRequests(request, type);
        if (itemRequests.isEmpty()) {
            throw new IllegalArgumentException("Phiếu kho phải có ít nhất 1 sản phẩm");
        }
        Warehouse warehouse = resolveWarehouse(request.getWarehouseId());
        if (type == WarehouseVoucherType.IMPORT) {
            lockVoucherWarehouse(warehouse);
        } else {
            requireWarehouse(warehouse.getId());
        }

        String voucherCode = request.getVoucherCode();
        if (voucherCode == null || voucherCode.isBlank()) {
            String prefix = switch (type) {
                case IMPORT -> "PNK";
                case EXPORT -> "PXK";
                case ADJUSTMENT -> "PDC";
            };
            voucherCode = prefix + "-" + UUID.randomUUID().toString();
        }
        if (voucherCode.length() > 50 || voucherRepository.existsByVoucherCode(voucherCode.trim())) {
            throw new IllegalArgumentException("Mã phiếu quá dài hoặc đã tồn tại");
        }

        // Kiểm tra nhà cung cấp nếu có cung cấp
        Supplier supplier = null;
        if (request.getSupplierId() != null || (request.getSupplierCode() != null && !request.getSupplierCode().isBlank())) {
            supplier = resolveSupplier(request.getSupplierId(), request.getSupplierCode());
            if (supplier != null && Boolean.FALSE.equals(supplier.getActive())) {
                throw new IllegalArgumentException("Nhà cung cấp '" + supplier.getName() + "' đã ngừng hoạt động");
            }
        }

        boolean isDraft = "DRAFT".equalsIgnoreCase(request.getStatus()) || Boolean.TRUE.equals(request.getDraft());
        String initialStatus = isDraft ? "DRAFT" : "RECORDED";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime voucherDate = request.getVoucherDate() != null ? request.getVoucherDate() : now;
        if (type == WarehouseVoucherType.IMPORT && !isDraft) validateImportHeader(supplier, request.getDocumentNumber(), request.getVoucherDate());

        WarehouseVoucher voucher = WarehouseVoucher.builder()
                .voucherCode(voucherCode)
                .documentNumber(request.getDocumentNumber())
                .voucherDate(voucherDate)
                .type(type)
                .warehouse(warehouse)
                .supplier(supplier)
                .note(request.getNote())
                .status(initialStatus)
                .createdBy(username != null ? username : "system")
                .recordedAt(isDraft ? null : now)
                .referenceVoucherId(request.getReferenceVoucherId())
                .referenceVoucherCode(request.getReferenceVoucherCode())
                .adjustmentReason(request.getAdjustmentReason())
                .overrideExpiredLot(false)
                .expiredLotOverrideReason(null)
                .items(new ArrayList<>())
                .build();

        boolean hasExpiredOverride = false;
        String voucherOverrideReason = null;



        for (WarehouseVoucherItemRequest itemReq : itemRequests) {
            if (itemReq == null) {
                throw new IllegalArgumentException("Dòng sản phẩm trong phiếu kho không được để trống");
            }
            Product product = resolveProduct(itemReq);
            if (!Boolean.TRUE.equals(product.getActive())) {
                throw new IllegalArgumentException("Sản phẩm '" + product.getName() + "' đã ngừng kinh doanh");
            }

            String inputUnit = itemReq.getUnit();
            if (inputUnit == null || inputUnit.isBlank()) {
                inputUnit = product.getUnit();
            } else {
                inputUnit = inputUnit.trim();
            }

            BigDecimal qty = itemReq.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng cho sản phẩm '" + product.getCode() + "' phải lớn hơn 0");
            }

            // Lấy hệ số quy đổi tại thời điểm lập phiếu (S2-07)
            BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);

            // Quy đổi về đơn vị cơ sở: baseQuantity = quantity * factor
            BigDecimal baseQty = qty.multiply(factor);

            // Lock physical stock before reading lots. Locked lot reads also see current
            // values under MySQL REPEATABLE READ, not an earlier transaction snapshot.
            WarehouseInventory exportInventory = null;
            List<WarehouseLotInventory> lockedExportLots = null;
            if (type == WarehouseVoucherType.EXPORT && !isDraft) {
                exportInventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                        .orElseThrow(() -> new IllegalArgumentException("Không đủ tồn kho để xuất cho sản phẩm '" + product.getName() + "'"));
                lockedExportLots = lotInventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId());
            }

            // Quản lý số lô và hạn sử dụng (S5-04, S6-01, S6-04)
            boolean isLotManaged = Boolean.TRUE.equals(product.getManageLot())
                    || type == WarehouseVoucherType.EXPORT && (lockedExportLots != null
                        ? lockedExportLots.stream().anyMatch(l -> l.getBaseQuantity() != null && l.getBaseQuantity().signum() > 0)
                        : lotInventoryRepository.existsByWarehouseIdAndProductIdAndBaseQuantityGreaterThan(warehouse.getId(), product.getId(), BigDecimal.ZERO));
            String effectiveLotNumber = itemReq.getLotNumber() != null ? itemReq.getLotNumber().trim() : null;
            LocalDate effectiveExpiryDate = itemReq.getExpiryDate();
            LocalDate effectiveMfgDate = itemReq.getManufacturingDate();
            String suggestedLotNumber = itemReq.getSuggestedLotNumber() != null ? itemReq.getSuggestedLotNumber().trim() : null;
            String overrideReason = itemReq.getEffectiveOverrideReason();

            Boolean itemOverrideExpired = null;
            String itemOverrideReason = null;

            if (type == WarehouseVoucherType.EXPORT) {
                if (isLotManaged) {
                    // S6-04: Mặc định gợi ý lô có hạn sử dụng gần nhất còn đủ số lượng (FEFO)
                    WarehouseLotInventory defaultSuggestedLot = lockedExportLots != null
                            ? chooseFefoLot(lockedExportLots, baseQty)
                            : findFefoSuggestedLot(warehouse.getId(), product.getId(), baseQty);
                    // Never trust a suggested lot supplied by the browser.
                    suggestedLotNumber = null;
                    if (defaultSuggestedLot != null) {
                        suggestedLotNumber = defaultSuggestedLot.getLotNumber();
                    }

                    if (effectiveLotNumber == null || effectiveLotNumber.isBlank()) {
                        // Nếu thủ kho không chọn lô, hệ thống mặc định chọn lô gợi ý FEFO còn đủ số lượng
                        if (defaultSuggestedLot == null) {
                            throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() +
                                    "' có quản lý lô nhưng không tìm thấy lô nào đủ số lượng xuất (" + baseQty + " " + product.getUnit() + "). Vui lòng chọn lô và nhập lý do.");
                        }
                        effectiveLotNumber = defaultSuggestedLot.getLotNumber();
                        effectiveExpiryDate = defaultSuggestedLot.getExpiryDate();
                        effectiveMfgDate = defaultSuggestedLot.getManufacturingDate();
                    } else {
                        // Thủ kho đã chọn một lô
                        final String requestedLot = effectiveLotNumber;
                        WarehouseLotInventory selectedLot = findExportLot(lockedExportLots, warehouse.getId(), product.getId(), requestedLot)
                                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lô '" + requestedLot + "' của sản phẩm '" + product.getName() + "' tại kho '" + warehouse.getName() + "'"));
                        if (selectedLot.getBaseQuantity() == null || selectedLot.getBaseQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                            throw new IllegalArgumentException("Lô '" + requestedLot + "' của sản phẩm '" + product.getName() + "' đã hết hàng trong kho");
                        }
                        if (effectiveExpiryDate == null) effectiveExpiryDate = selectedLot.getExpiryDate();
                        if (effectiveMfgDate == null) effectiveMfgDate = selectedLot.getManufacturingDate();

                        // S6-04: Thủ kho chọn lô khác được nhưng phải nhập lý do
                        if (suggestedLotNumber != null && !requestedLot.equalsIgnoreCase(suggestedLotNumber)) {
                            if (overrideReason == null || overrideReason.isBlank()) {
                                overrideReason = itemReq.getNote();
                            }
                            if (overrideReason == null || overrideReason.isBlank()) {
                                throw new IllegalArgumentException("Thủ kho chọn lô '" + requestedLot + "' khác với lô gợi ý hạn dùng gần nhất (" + suggestedLotNumber + "), bắt buộc phải nhập lý do");
                            }
                        }
                    }
                } else if (effectiveLotNumber != null && !effectiveLotNumber.isBlank()) {
                    WarehouseLotInventory selectedLot = findExportLot(lockedExportLots, warehouse.getId(), product.getId(), effectiveLotNumber)
                            .orElse(null);
                    if (selectedLot != null) {
                        if (effectiveExpiryDate == null) effectiveExpiryDate = selectedLot.getExpiryDate();
                        if (effectiveMfgDate == null) effectiveMfgDate = selectedLot.getManufacturingDate();
                    }
                }

                if (effectiveLotNumber != null && !effectiveLotNumber.isBlank()) {
                    WarehouseLotInventory existingLot = findExportLot(lockedExportLots, warehouse.getId(), product.getId(), effectiveLotNumber)
                            .orElse(null);

                    if (!isDraft && existingLot != null && existingLot.getBaseQuantity() != null && existingLot.getBaseQuantity().compareTo(baseQty) < 0) {
                        throw new IllegalArgumentException("Không đủ tồn kho theo lô để xuất cho sản phẩm '" + product.getName() +
                                "'. Lô '" + effectiveLotNumber + "' không đủ tồn kho để xuất. Tồn lô hiện tại: " + existingLot.getBaseQuantity() + " " + product.getUnit() +
                                ", yêu cầu xuất: " + baseQty + " " + product.getUnit());
                    }

                    // The stored lot is authoritative: client dates cannot make an expired lot safe.
                    if (existingLot != null) {
                        effectiveExpiryDate = existingLot.getExpiryDate();
                        effectiveMfgDate = existingLot.getManufacturingDate();
                        if (!isDraft && effectiveExpiryDate == null) {
                            throw new IllegalArgumentException("Lô chưa có hạn sử dụng trong dữ liệu kho, không thể xuất.");
                        }
                    }
                    // S6-01: Không cho xuất lô đã quá hạn, trừ khi Quản lý kho xác nhận ghi đè kèm lý do
                    boolean isExpired = effectiveExpiryDate != null && effectiveExpiryDate.isBefore(LocalDate.now());
                    if (isExpired) {
                        if (!canOverrideExpiredLot()) {
                            throw new AccessDeniedException("Lô hàng '" + effectiveLotNumber + "' của sản phẩm '" + product.getName() +
                                    "' đã quá hạn sử dụng (" + effectiveExpiryDate + "). Chỉ Quản lý kho mới có quyền xác nhận ghi đè xuất lô đã quá hạn.");
                        }

                        boolean overrideConfirmed = Boolean.TRUE.equals(request.getOverrideExpiredLot())
                                || Boolean.TRUE.equals(itemReq.getOverrideExpiredLot());
                        String expiredOverrideReason = itemReq.getExpiredLotOverrideReason() != null && !itemReq.getExpiredLotOverrideReason().isBlank()
                                ? itemReq.getExpiredLotOverrideReason().trim()
                                : (request.getExpiredLotOverrideReason() != null ? request.getExpiredLotOverrideReason().trim() : null);

                        if (!overrideConfirmed) {
                            throw new IllegalArgumentException("Lô hàng '" + effectiveLotNumber + "' của sản phẩm '" + product.getName() +
                                    "' đã quá hạn sử dụng (" + effectiveExpiryDate + "). Cần Quản lý kho xác nhận ghi đè để xuất hàng.");
                        }
                        if (expiredOverrideReason == null || expiredOverrideReason.isBlank()) {
                            throw new IllegalArgumentException("Bắt buộc phải nhập lý do khi Quản lý kho xác nhận ghi đè xuất lô đã quá hạn '" + effectiveLotNumber + "'.");
                        }

                        itemOverrideExpired = true;
                        itemOverrideReason = expiredOverrideReason;
                        hasExpiredOverride = true;
                        voucherOverrideReason = expiredOverrideReason;

                        auditLogService.logAction(
                                "WAREHOUSE_LOT_OVERRIDE",
                                warehouse.getId() + ":" + product.getId() + ":" + effectiveLotNumber,
                                "EXPIRED_LOT_OVERRIDE",
                                null,
                                "Xuất lô quá hạn: " + effectiveLotNumber + " (HSD: " + effectiveExpiryDate + "). Lý do: " + expiredOverrideReason,
                                username != null ? username : "system"
                        );
                    }
                }
            } else if (isLotManaged) {
                if (effectiveLotNumber == null || effectiveLotNumber.isBlank()) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập số lô");
                }
                if (effectiveExpiryDate == null) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập hạn sử dụng");
                }
                if (effectiveMfgDate != null && effectiveExpiryDate.isBefore(effectiveMfgDate)) {
                    throw new IllegalArgumentException("Hạn sử dụng không được trước ngày sản xuất cho lô '" + effectiveLotNumber + "'");
                }
            }

            final String finalLotNumber = effectiveLotNumber;
            final LocalDate finalExpiryDate = effectiveExpiryDate;
            final LocalDate finalMfgDate = effectiveMfgDate;

            BigDecimal price = itemReq.getPrice() != null ? itemReq.getPrice() : product.getPrice();
            BigDecimal totalAmount = price != null ? price.multiply(qty) : BigDecimal.ZERO;

            // ĐÓNG BĂNG thông tin đơn vị, hệ số và số lượng cơ sở vào dòng phiếu
            WarehouseVoucherItem item = WarehouseVoucherItem.builder()
                    .voucher(voucher)
                    .product(product)
                    .unit(inputUnit)
                    .quantity(qty)
                    .conversionFactor(factor)
                    .baseUnit(product.getUnit())
                    .baseQuantity(baseQty)
                    .price(price)
                    .totalAmount(totalAmount)
                    .lotNumber(finalLotNumber)
                    .expiryDate(finalExpiryDate)
                    .manufacturingDate(finalMfgDate)
                    .suggestedLotNumber(suggestedLotNumber)
                    .overrideReason(overrideReason)
                    .overrideExpiredLot(itemOverrideExpired)
                    .expiredLotOverrideReason(itemOverrideReason)
                    .note(itemReq.getNote())
                    .build();

            voucher.addItem(item);

            // CHỈ CỘNG TỒN KHO KHI XÁC NHẬN / GHI SỔ, PHIẾU NHÁP KHÔNG ẢNH HƯỞNG TỒN (S5-04)
            if (!isDraft) {
                WarehouseInventory inventory = exportInventory != null ? exportInventory : inventoryRepository
                        .findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                        .orElseGet(() -> WarehouseInventory.builder()
                                .warehouse(warehouse)
                                .product(product)
                                .baseQuantity(BigDecimal.ZERO)
                                .baseUnit(product.getUnit())
                                .build());
                BigDecimal quantityBefore = inventory.getBaseQuantity() == null
                        ? BigDecimal.ZERO : inventory.getBaseQuantity();

                if (type == WarehouseVoucherType.IMPORT) {
                    inventory.addBaseQuantity(baseQty);
                } else if (type == WarehouseVoucherType.EXPORT) {
                    if (inventory.getAvailableQuantity().compareTo(baseQty) < 0) {
                        throw new IllegalArgumentException("Không đủ tồn kho để xuất cho sản phẩm '" + product.getName() +
                                "'. Tồn khả dụng hiện tại: " + inventory.getAvailableQuantity() + " " + product.getUnit() +
                                ", yêu cầu xuất: " + baseQty + " " + product.getUnit() + " (" + qty + " " + inputUnit + ")");
                    }
                    inventory.subtractBaseQuantity(baseQty);
                } else if (type == WarehouseVoucherType.ADJUSTMENT) {
                    BigDecimal adjusted = request.getDelta() != null
                            ? quantityBefore.add(request.getDelta().multiply(factor)) : baseQty;
                    BigDecimal reserved = inventory.getReservedQuantity() == null ? BigDecimal.ZERO : inventory.getReservedQuantity();
                    if (adjusted.signum() < 0 || adjusted.compareTo(reserved) < 0)
                        throw new IllegalArgumentException("Điều chỉnh không được làm âm tồn hoặc giảm dưới số lượng đang giữ chỗ");
                    inventory.setBaseQuantity(adjusted);
                    // A stock-card adjustment is the signed movement, not its target balance.
                    item.setBaseQuantity(inventory.getBaseQuantity().subtract(quantityBefore));
                }

                inventoryRepository.save(inventory);

                // Cập nhật tồn kho theo lô nếu có lotNumber (S5-04, S6-01, S6-04)
                if (finalLotNumber != null && !finalLotNumber.isBlank()) {
                    if (type == WarehouseVoucherType.EXPORT) {
                        WarehouseLotInventory lotInv = findExportLot(lockedExportLots, warehouse.getId(), product.getId(), finalLotNumber)
                                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lô '" + finalLotNumber + "' của sản phẩm '" + product.getName() + "' trong kho để xuất"));
                        BigDecimal currentLotQty = lotInv.getBaseQuantity() != null ? lotInv.getBaseQuantity() : BigDecimal.ZERO;
                        if (currentLotQty.compareTo(baseQty) < 0) {
                            throw new IllegalArgumentException("Lô '" + finalLotNumber + "' không đủ tồn kho để xuất. Tồn hiện tại: " +
                                    currentLotQty + " " + product.getUnit() + ", yêu cầu xuất: " + baseQty + " " + product.getUnit());
                        }
                        lotInv.subtractBaseQuantity(baseQty);
                        lotInventoryRepository.save(lotInv);
                    } else {
                        WarehouseLotInventory lotInv = lotInventoryRepository
                                .findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), finalLotNumber)
                                .orElseGet(() -> WarehouseLotInventory.builder()
                                        .warehouse(warehouse)
                                        .product(product)
                                        .lotNumber(finalLotNumber)
                                        .expiryDate(finalExpiryDate)
                                        .manufacturingDate(finalMfgDate)
                                        .baseQuantity(BigDecimal.ZERO)
                                        .baseUnit(product.getUnit())
                                        .build());
                        requireSameLotDates(lotInv, finalExpiryDate, finalMfgDate);
                        if (type == WarehouseVoucherType.IMPORT) {
                            lotInv.addBaseQuantity(baseQty);
                        }
                        lotInventoryRepository.save(lotInv);
                    }
                }

                auditLogService.logAction(
                        "INVENTORY",
                        warehouse.getCode() + ":" + product.getCode(),
                        type.name(),
                        quantityBefore.toPlainString() + " " + product.getUnit(),
                        inventory.getBaseQuantity().toPlainString() + " " + product.getUnit(),
                        username != null ? username : "system");
            }
        }

        if (hasExpiredOverride) {
            voucher.setOverrideExpiredLot(true);
            voucher.setExpiredLotOverrideReason(voucherOverrideReason);
        }

        WarehouseVoucher savedVoucher = voucherRepository.save(voucher);
        return mapToVoucherResponse(savedVoucher);
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse confirmVoucher(Long id, String username) {
        requireGrant("WAREHOUSE_APPROVE");
        WarehouseVoucher voucher = voucherRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho với ID: " + id));
        requireWarehouse(voucher.getWarehouse().getId());

        if (!"DRAFT".equalsIgnoreCase(voucher.getStatus())) {
            throw new IllegalStateException("Chỉ có thể xác nhận phiếu ở trạng thái Nháp (DRAFT). Trạng thái hiện tại: " + voucher.getStatus());
        }

        Warehouse warehouse = voucher.getWarehouse();
        if (warehouse == null || !Boolean.TRUE.equals(warehouse.getActive())) {
            throw new IllegalArgumentException("Kho của phiếu nhập đã ngừng hoạt động hoặc không tồn tại");
        }

        if (voucher.getItems() == null || voucher.getItems().isEmpty()) {
            throw new IllegalArgumentException("Phiếu kho không có sản phẩm để xác nhận");
        }

        warehouse = lockVoucherWarehouse(warehouse);
        final Warehouse postingWarehouse = warehouse;
        if (voucher.getType() != WarehouseVoucherType.IMPORT) throw new IllegalArgumentException("Chỉ xác nhận phiếu nhập kho qua chức năng này");
        validateImportHeader(voucher.getSupplier(), voucher.getDocumentNumber(), voucher.getVoucherDate());
        voucher.getItems().sort(Comparator.comparing(i -> i.getProduct().getId()));

        for (WarehouseVoucherItem item : voucher.getItems()) {
            Product product = item.getProduct();
            if (product == null || !Boolean.TRUE.equals(product.getActive())) {
                throw new IllegalArgumentException("Sản phẩm '" + (product != null ? product.getName() : "") + "' đã ngừng kinh doanh");
            }

            // Kiểm tra ràng buộc lô
            if (Boolean.TRUE.equals(product.getManageLot())) {
                if (item.getLotNumber() == null || item.getLotNumber().isBlank()) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập số lô");
                }
                if (item.getExpiryDate() == null) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập hạn sử dụng");
                }
            }

            // A draft is a preview, not a frozen accounting entry. Freeze the current conversion at posting.
            BigDecimal factor = unitConversionService.getConversionFactor(product, item.getUnit());
            BigDecimal baseQty = item.getQuantity().multiply(factor);
            item.setConversionFactor(factor);
            item.setBaseUnit(product.getUnit());
            item.setBaseQuantity(baseQty);

            // S6-01: Kiểm tra xuất lô quá hạn khi xác nhận phiếu xuất kho
            if (voucher.getType() == WarehouseVoucherType.EXPORT && item.getLotNumber() != null && !item.getLotNumber().isBlank()) {
                WarehouseLotInventory lotInv = lotInventoryRepository
                        .findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), item.getLotNumber())
                        .orElse(null);
                if (lotInv != null && lotInv.getBaseQuantity() != null && lotInv.getBaseQuantity().compareTo(baseQty) < 0) {
                    throw new IllegalArgumentException("Không đủ tồn kho theo lô để xuất cho sản phẩm '" + product.getName() +
                            "', lô '" + item.getLotNumber() + "'.");
                }
                LocalDate expiry = item.getExpiryDate() != null ? item.getExpiryDate() : (lotInv != null ? lotInv.getExpiryDate() : null);
                if (expiry != null && expiry.isBefore(LocalDate.now())) {
                    if (!canOverrideExpiredLot()) {
                        throw new AccessDeniedException("Lô hàng '" + item.getLotNumber() + "' của sản phẩm '" + product.getName() +
                                "' đã quá hạn sử dụng (" + expiry + "). Chỉ Quản lý kho mới có quyền xác nhận ghi đè xuất lô đã quá hạn.");
                    }
                    boolean overrideConfirmed = Boolean.TRUE.equals(voucher.getOverrideExpiredLot())
                            || Boolean.TRUE.equals(item.getOverrideExpiredLot());
                    String overrideReason = item.getExpiredLotOverrideReason() != null && !item.getExpiredLotOverrideReason().isBlank()
                            ? item.getExpiredLotOverrideReason().trim()
                            : (voucher.getExpiredLotOverrideReason() != null ? voucher.getExpiredLotOverrideReason().trim() : null);
                    if (!overrideConfirmed) {
                        throw new IllegalArgumentException("Lô hàng '" + item.getLotNumber() + "' của sản phẩm '" + product.getName() +
                                "' đã quá hạn sử dụng (" + expiry + "). Cần Quản lý kho xác nhận ghi đè để xuất hàng.");
                    }
                    if (overrideReason == null || overrideReason.isBlank()) {
                        throw new IllegalArgumentException("Bắt buộc phải nhập lý do khi Quản lý kho xác nhận ghi đè xuất lô đã quá hạn '" + item.getLotNumber() + "'.");
                    }
                }
            }

            // Cập nhật sổ sách tồn kho tổng
            WarehouseInventory inventory = inventoryRepository
                    .findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElseGet(() -> WarehouseInventory.builder()
                            .warehouse(postingWarehouse)
                            .product(product)
                            .baseQuantity(BigDecimal.ZERO)
                            .baseUnit(product.getUnit())
                            .build());

            BigDecimal quantityBefore = inventory.getBaseQuantity() == null ? BigDecimal.ZERO : inventory.getBaseQuantity();

            if (voucher.getType() == WarehouseVoucherType.IMPORT) {
                inventory.addBaseQuantity(baseQty);
            } else if (voucher.getType() == WarehouseVoucherType.EXPORT) {
                if (inventory.getBaseQuantity().compareTo(baseQty) < 0) {
                    throw new IllegalArgumentException("Không đủ tồn kho để xuất cho sản phẩm '" + product.getName() +
                            "'. Tồn hiện tại: " + inventory.getBaseQuantity() + " " + product.getUnit() +
                            ", yêu cầu: " + baseQty + " " + product.getUnit());
                }
                inventory.subtractBaseQuantity(baseQty);
            }

            inventoryRepository.save(inventory);

            // Cập nhật tồn kho theo lô
            if (item.getLotNumber() != null && !item.getLotNumber().isBlank()) {
                WarehouseLotInventory lotInv = lotInventoryRepository
                        .findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), item.getLotNumber())
                        .orElseGet(() -> WarehouseLotInventory.builder()
                                .warehouse(postingWarehouse)
                                .product(product)
                                .lotNumber(item.getLotNumber())
                                .expiryDate(item.getExpiryDate())
                                .manufacturingDate(item.getManufacturingDate())
                                .baseQuantity(BigDecimal.ZERO)
                                .baseUnit(product.getUnit())
                                .build());
                requireSameLotDates(lotInv, item.getExpiryDate(), item.getManufacturingDate());
                if (voucher.getType() == WarehouseVoucherType.IMPORT) {
                    lotInv.addBaseQuantity(baseQty);
                } else if (voucher.getType() == WarehouseVoucherType.EXPORT) {
                    lotInv.subtractBaseQuantity(baseQty);
                }
                lotInventoryRepository.save(lotInv);
            }

            auditLogService.logAction(
                    "INVENTORY",
                    warehouse.getCode() + ":" + product.getCode(),
                    voucher.getType().name() + "_CONFIRM",
                    quantityBefore.toPlainString() + " " + product.getUnit(),
                    inventory.getBaseQuantity().toPlainString() + " " + product.getUnit(),
                    username != null ? username : "system");
        }

        voucher.setStatus("CONFIRMED");
        voucher.setRecordedAt(LocalDateTime.now());
        WarehouseVoucher saved = voucherRepository.save(voucher);
        return mapToVoucherResponse(saved);
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse updateVoucher(Long id, WarehouseVoucherRequest request, String username) {
        requireGrant("WAREHOUSE_IMPORT");
        WarehouseVoucher voucher = voucherRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho với ID: " + id));
        requireWarehouse(voucher.getWarehouse().getId());
        if (voucher.getType() != WarehouseVoucherType.IMPORT) throw new IllegalArgumentException("Chỉ sửa nháp phiếu nhập kho");
        if (request.getVersion() != null && !Objects.equals(request.getVersion(), voucher.getVersion())) {
            throw new IllegalStateException("Phiếu đã được cập nhật ở phiên khác. Hãy tải lại trước khi sửa.");
        }

        // Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh (S5-04)
        if ("CONFIRMED".equalsIgnoreCase(voucher.getStatus()) || "RECORDED".equalsIgnoreCase(voucher.getStatus()) || "ADJUSTED".equalsIgnoreCase(voucher.getStatus())) {
            throw new IllegalStateException("Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh");
        }
        if (!"DRAFT".equalsIgnoreCase(voucher.getStatus())) {
            throw new IllegalStateException("Chỉ được sửa phiếu ở trạng thái Nháp (DRAFT). Trạng thái hiện tại: " + voucher.getStatus());
        }

        if (request.getWarehouseId() != null) {
            requireWarehouse(request.getWarehouseId());
            voucher.setWarehouse(resolveWarehouse(request.getWarehouseId()));
        }

        if (request.getSupplierId() != null || (request.getSupplierCode() != null && !request.getSupplierCode().isBlank())) {
            Supplier supplier = resolveSupplier(request.getSupplierId(), request.getSupplierCode());
            if (supplier != null && Boolean.FALSE.equals(supplier.getActive())) {
                throw new IllegalArgumentException("Nhà cung cấp '" + supplier.getName() + "' đã ngừng hoạt động");
            }
            voucher.setSupplier(supplier);
        }

        if (request.getDocumentNumber() != null) {
            voucher.setDocumentNumber(request.getDocumentNumber());
        }
        if (request.getVoucherDate() != null) {
            voucher.setVoucherDate(request.getVoucherDate());
        }
        if (request.getNote() != null) {
            voucher.setNote(request.getNote());
        }

        // Cập nhật danh sách items nếu có truyền
        List<WarehouseVoucherItemRequest> itemRequests = normalizeItemRequests(request, voucher.getType());
        if (!itemRequests.isEmpty()) {
            voucher.getItems().clear();


        for (WarehouseVoucherItemRequest itemReq : itemRequests) {
                Product product = resolveProduct(itemReq);
                if (!Boolean.TRUE.equals(product.getActive())) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getName() + "' đã ngừng kinh doanh");
                }

                String inputUnit = itemReq.getUnit();
                if (inputUnit == null || inputUnit.isBlank()) {
                    inputUnit = product.getUnit();
                } else {
                    inputUnit = inputUnit.trim();
                }

                BigDecimal qty = itemReq.getQuantity();
                if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Số lượng cho sản phẩm '" + product.getCode() + "' phải lớn hơn 0");
                }

                BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);
                BigDecimal baseQty = qty.multiply(factor);

                boolean isLotManaged = Boolean.TRUE.equals(product.getManageLot());
                String lotNumber = itemReq.getLotNumber() != null ? itemReq.getLotNumber().trim() : null;
                LocalDate expiryDate = itemReq.getExpiryDate();
                LocalDate mfgDate = itemReq.getManufacturingDate();

                if (isLotManaged) {
                    if (lotNumber == null || lotNumber.isBlank()) {
                        throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập số lô");
                    }
                    if (expiryDate == null) {
                        throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập hạn sử dụng");
                    }
                    if (mfgDate != null && expiryDate.isBefore(mfgDate)) {
                        throw new IllegalArgumentException("Hạn sử dụng không được trước ngày sản xuất cho lô '" + lotNumber + "'");
                    }
                }

                BigDecimal price = itemReq.getPrice() != null ? itemReq.getPrice() : product.getPrice();
                BigDecimal totalAmount = price != null ? price.multiply(qty) : BigDecimal.ZERO;

                WarehouseVoucherItem item = WarehouseVoucherItem.builder()
                        .voucher(voucher)
                        .product(product)
                        .unit(inputUnit)
                        .quantity(qty)
                        .conversionFactor(factor)
                        .baseUnit(product.getUnit())
                        .baseQuantity(baseQty)
                        .price(price)
                        .totalAmount(totalAmount)
                        .lotNumber(lotNumber)
                        .expiryDate(expiryDate)
                        .manufacturingDate(mfgDate)
                        .note(itemReq.getNote())
                        .build();

                voucher.addItem(item);
            }
        }

        WarehouseVoucher saved = voucherRepository.saveAndFlush(voucher);
        return mapToVoucherResponse(saved);
    }

    @Override
    @Transactional
    public void deleteVoucher(Long id, String username) {
        requireGrant("WAREHOUSE_IMPORT");
        WarehouseVoucher voucher = voucherRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho với ID: " + id));
        requireWarehouse(voucher.getWarehouse().getId());
        if (voucher.getType() != WarehouseVoucherType.IMPORT || !"DRAFT".equalsIgnoreCase(voucher.getStatus())) {
            throw new IllegalStateException("Chỉ được xóa phiếu nhập nháp, phiếu đã xác nhận chỉ lập phiếu điều chỉnh");
        }

        // Phiếu đã xác nhận không thể xóa, chỉ lập phiếu điều chỉnh (S5-04)
        if ("CONFIRMED".equalsIgnoreCase(voucher.getStatus()) || "RECORDED".equalsIgnoreCase(voucher.getStatus()) || "ADJUSTED".equalsIgnoreCase(voucher.getStatus())) {
            throw new IllegalStateException("Phiếu đã xác nhận không thể xóa, chỉ lập phiếu điều chỉnh");
        }

        voucherRepository.delete(voucher);
        auditLogService.logAction(
                "WAREHOUSE_VOUCHER",
                voucher.getVoucherCode(),
                "DELETE_DRAFT",
                voucher.getStatus(),
                "DELETED",
                username != null ? username : "system");
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse adjustVoucher(Long id, WarehouseVoucherRequest request, String username) {
        requireGrant("WAREHOUSE_ADJUST");
        WarehouseVoucher originalVoucher = voucherRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho gốc với ID: " + id));

        if (originalVoucher.getShipmentOrderId() != null) {
            throw new IllegalArgumentException("Phiếu xuất theo đơn đã xác nhận không sửa/điều chỉnh trực tiếp; dùng nghiệp vụ trả hàng");
        }

        if (!"CONFIRMED".equalsIgnoreCase(originalVoucher.getStatus()) && !"RECORDED".equalsIgnoreCase(originalVoucher.getStatus()) && !"ADJUSTED".equalsIgnoreCase(originalVoucher.getStatus())) {
            throw new IllegalStateException("Chỉ có thể lập phiếu điều chỉnh cho phiếu kho đã xác nhận. Trạng thái hiện tại: " + originalVoucher.getStatus());
        }

        if (request == null) {
            throw new IllegalArgumentException("Không được để trống nội dung phiếu điều chỉnh");
        }

        if (request.getAdjustmentReason() == null || request.getAdjustmentReason().isBlank()) {
            throw new IllegalArgumentException("Bắt buộc nhập lý do điều chỉnh");
        }
        if (request.getWarehouseId() != null && !request.getWarehouseId().equals(originalVoucher.getWarehouse().getId())) {
            throw new IllegalArgumentException("Phiếu điều chỉnh phải cùng kho với phiếu gốc");
        }
        lockVoucherWarehouse(originalVoucher.getWarehouse());

        Warehouse warehouse = request.getWarehouseId() != null
                ? resolveWarehouse(request.getWarehouseId())
                : originalVoucher.getWarehouse();

        String voucherCode = request.getVoucherCode();
        if (voucherCode == null || voucherCode.isBlank()) {
            voucherCode = "PDC-" + UUID.randomUUID();
        }

        String reason = request.getAdjustmentReason() != null && !request.getAdjustmentReason().isBlank()
                ? request.getAdjustmentReason()
                : (request.getNote() != null ? request.getNote() : "Điều chỉnh cho phiếu " + originalVoucher.getVoucherCode());

        WarehouseVoucher adjustmentVoucher = WarehouseVoucher.builder()
                .voucherCode(voucherCode)
                .documentNumber(request.getDocumentNumber())
                .type(WarehouseVoucherType.ADJUSTMENT)
                .warehouse(warehouse)
                .supplier(originalVoucher.getSupplier())
                .referenceVoucherId(originalVoucher.getId())
                .referenceVoucherCode(originalVoucher.getVoucherCode())
                .adjustmentReason(reason)
                .note(request.getNote())
                .status("CONFIRMED")
                .createdBy(username != null ? username : "system")
                .voucherDate(request.getVoucherDate() != null ? request.getVoucherDate() : LocalDateTime.now())
                .recordedAt(LocalDateTime.now())
                .items(new ArrayList<>())
                .build();

        List<WarehouseVoucherItemRequest> itemRequests = normalizeItemRequests(request, WarehouseVoucherType.ADJUSTMENT);
        if (itemRequests.isEmpty()) {
            throw new IllegalArgumentException("Phiếu điều chỉnh phải có ít nhất 1 sản phẩm");
        }

        Set<String> adjustedKeys = new HashSet<>();
        for (WarehouseVoucherItemRequest itemReq : itemRequests) {
            Product product = resolveProduct(itemReq);
            if (!Boolean.TRUE.equals(product.getActive())) throw new IllegalArgumentException("Sản phẩm đã ngừng kinh doanh");
            String key = product.getId() + ":" + Objects.toString(itemReq.getLotNumber(), "").trim();
            if (!adjustedKeys.add(key)) throw new IllegalArgumentException("Không lặp cùng mặt hàng/lô trong phiếu điều chỉnh");

            String inputUnit = itemReq.getUnit();
            if (inputUnit == null || inputUnit.isBlank()) {
                inputUnit = product.getUnit();
            } else {
                inputUnit = inputUnit.trim();
            }

            BigDecimal qty = itemReq.getQuantity() != null ? itemReq.getQuantity() : BigDecimal.ZERO;
            BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);
            BigDecimal baseQty = qty.multiply(factor);

            String lotNumber = itemReq.getLotNumber() != null ? itemReq.getLotNumber().trim() : null;
            LocalDate expiryDate = itemReq.getExpiryDate();
            LocalDate mfgDate = itemReq.getManufacturingDate();

            if (Boolean.TRUE.equals(product.getManageLot()) && (lotNumber == null || lotNumber.isBlank() || expiryDate == null)) {
                throw new IllegalArgumentException("Mặt hàng quản lý lô phải có số lô và hạn sử dụng");
            }

            BigDecimal price = itemReq.getPrice() != null ? itemReq.getPrice() : product.getPrice();
            BigDecimal totalAmount = price != null ? price.multiply(qty) : BigDecimal.ZERO;

            WarehouseVoucherItem item = WarehouseVoucherItem.builder()
                    .voucher(adjustmentVoucher)
                    .product(product)
                    .unit(inputUnit)
                    .quantity(qty)
                    .conversionFactor(factor)
                    .baseUnit(product.getUnit())
                    .baseQuantity(baseQty)
                    .price(price)
                    .totalAmount(totalAmount)
                    .lotNumber(lotNumber)
                    .expiryDate(expiryDate)
                    .manufacturingDate(mfgDate)
                    .note(itemReq.getNote())
                    .build();

            adjustmentVoucher.addItem(item);

            // Cập nhật tồn kho theo delta hoặc số lượng điều chỉnh
            WarehouseInventory inventory = inventoryRepository
                    .findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElseGet(() -> WarehouseInventory.builder()
                            .warehouse(warehouse)
                            .product(product)
                            .baseQuantity(BigDecimal.ZERO)
                            .baseUnit(product.getUnit())
                            .build());

            BigDecimal quantityBefore = inventory.getBaseQuantity() == null ? BigDecimal.ZERO : inventory.getBaseQuantity();

            // Cập nhật tồn kho lô nếu có
            BigDecimal movement;
            if (lotNumber != null && !lotNumber.isBlank()) {
                WarehouseLotInventory lotInv = lotInventoryRepository
                        .findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), lotNumber)
                        .orElseGet(() -> WarehouseLotInventory.builder()
                                .warehouse(warehouse)
                                .product(product)
                                .lotNumber(lotNumber)
                                .expiryDate(expiryDate)
                                .manufacturingDate(mfgDate)
                                .baseQuantity(BigDecimal.ZERO)
                                .baseUnit(product.getUnit())
                                .build());
                requireSameLotDates(lotInv, expiryDate, mfgDate);
                movement = request.getDelta() != null ? request.getDelta().multiply(factor) : baseQty.subtract(lotInv.getBaseQuantity());
                BigDecimal newLotQty = lotInv.getBaseQuantity().add(movement);
                if (newLotQty.signum() < 0) throw new IllegalArgumentException("Điều chỉnh làm âm tồn lô");
                lotInv.setBaseQuantity(newLotQty);
                lotInventoryRepository.save(lotInv);
            } else {
                movement = request.getDelta() != null ? request.getDelta().multiply(factor) : baseQty.subtract(quantityBefore);
            }
            BigDecimal newQuantity = quantityBefore.add(movement);
            BigDecimal reserved = inventory.getReservedQuantity() == null ? BigDecimal.ZERO : inventory.getReservedQuantity();
            if (newQuantity.signum() < 0 || newQuantity.compareTo(reserved) < 0) {
                throw new IllegalArgumentException("Điều chỉnh không được làm âm tồn hoặc giảm dưới số lượng đang giữ chỗ");
            }
            inventory.setBaseQuantity(newQuantity);
            inventoryRepository.save(inventory);
            // The adjustment line records the signed movement, not a misleading positive target.
            item.setBaseQuantity(movement);
            item.setQuantity(movement.divide(factor, 4, RoundingMode.HALF_UP));

            auditLogService.logAction(
                    "INVENTORY",
                    warehouse.getCode() + ":" + product.getCode(),
                    "ADJUSTMENT",
                    quantityBefore.toPlainString() + " " + product.getUnit(),
                    inventory.getBaseQuantity().toPlainString() + " " + product.getUnit(),
                    username != null ? username : "system");
        }

        originalVoucher.setStatus("ADJUSTED");
        voucherRepository.save(originalVoucher);

        WarehouseVoucher saved = voucherRepository.save(adjustmentVoucher);
        return mapToVoucherResponse(saved);
    }

    private WarehouseLotInventoryResponse mapToLotInventoryResponse(WarehouseLotInventory lot, Integer customThreshold) {
        LocalDate today = LocalDate.now();
        LocalDate expiryDate = lot.getExpiryDate();
        Long daysUntilExpiry = null;
        boolean isExpired = false;
        boolean isExpiringSoon = false;
        String status = "ACTIVE";

        Product p = lot.getProduct();
        int threshold = (customThreshold != null && customThreshold > 0)
                ? customThreshold
                : (p != null && p.getExpiryWarningDays() != null && p.getExpiryWarningDays() > 0 ? p.getExpiryWarningDays() : 30);

        if (expiryDate != null) {
            daysUntilExpiry = ChronoUnit.DAYS.between(today, expiryDate);
            if (expiryDate.isBefore(today)) {
                isExpired = true;
                status = "EXPIRED";
            } else if (daysUntilExpiry <= threshold) {
                isExpiringSoon = true;
                status = "EXPIRING_SOON";
            } else {
                status = "ACTIVE";
            }
        }

        return WarehouseLotInventoryResponse.builder()
                .id(lot.getId())
                .warehouseId(lot.getWarehouse() != null ? lot.getWarehouse().getId() : null)
                .warehouseCode(lot.getWarehouse() != null ? lot.getWarehouse().getCode() : null)
                .warehouseName(lot.getWarehouse() != null ? lot.getWarehouse().getName() : null)
                .productId(p != null ? p.getId() : null)
                .productCode(p != null ? p.getCode() : null)
                .productName(p != null ? p.getName() : null)
                .lotNumber(lot.getLotNumber())
                .expiryDate(lot.getExpiryDate())
                .manufacturingDate(lot.getManufacturingDate())
                .baseQuantity(lot.getBaseQuantity())
                .baseUnit(lot.getBaseUnit())
                .updatedAt(lot.getUpdatedAt())
                .warningThresholdDays(threshold)
                .daysUntilExpiry(daysUntilExpiry)
                .isExpired(isExpired)
                .isExpiringSoon(isExpiringSoon)
                .status(status)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseLotInventoryResponse> getLotInventory(Long warehouseId, Long productId) {
        return getLotInventory(warehouseId, productId, null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseLotInventoryResponse> getLotInventory(
            Long warehouseId, Long productId, String status, Integer thresholdDays, String keyword) {
        if (warehouseId != null) requireWarehouse(warehouseId);
        if (thresholdDays != null && thresholdDays < 1) throw new IllegalArgumentException("Ngưỡng cảnh báo phải ít nhất 1 ngày");
        if (status != null && !status.isBlank() && !Set.of("ALL", "ACTIVE", "EXPIRING_SOON", "EXPIRED").contains(status.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("Trạng thái hạn sử dụng không hợp lệ");
        List<WarehouseLotInventory> list;
        if (warehouseId != null && productId != null) {
            list = lotInventoryRepository.findByWarehouseIdAndProductId(warehouseId, productId);
        } else if (warehouseId != null) {
            list = lotInventoryRepository.findByWarehouseId(warehouseId);
        } else if (productId != null) {
            list = lotInventoryRepository.findByProductId(productId);
        } else {
            list = lotInventoryRepository.findAllByOrderByUpdatedAtDesc();
        }

        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim().toLowerCase() : null;

        return list.stream()
                .filter(lot -> lot.getWarehouse() != null && mayAccessWarehouse(lot.getWarehouse().getId()))
                .filter(lot -> {
                    if (kw == null) return true;
                    boolean matchLot = lot.getLotNumber() != null && lot.getLotNumber().toLowerCase().contains(kw);
                    boolean matchProdCode = lot.getProduct() != null && lot.getProduct().getCode() != null && lot.getProduct().getCode().toLowerCase().contains(kw);
                    boolean matchProdName = lot.getProduct() != null && lot.getProduct().getName() != null && lot.getProduct().getName().toLowerCase().contains(kw);
                    return matchLot || matchProdCode || matchProdName;
                })
                .map(lot -> mapToLotInventoryResponse(lot, thresholdDays))
                .filter(resp -> {
                    if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) return true;
                    if ("EXPIRED".equalsIgnoreCase(status)) return Boolean.TRUE.equals(resp.getIsExpired());
                    if ("EXPIRING_SOON".equalsIgnoreCase(status)) return Boolean.TRUE.equals(resp.getIsExpiringSoon());
                    if ("ACTIVE".equalsIgnoreCase(status)) return !Boolean.TRUE.equals(resp.getIsExpired()) && !Boolean.TRUE.equals(resp.getIsExpiringSoon());
                    return true;
                })
                .sorted(Comparator.comparing(WarehouseLotInventoryResponse::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public LotExpiryAlertResponse getLotExpiryAlerts(
            Long warehouseId, Long productId, Integer customThresholdDays, String status) {
        List<WarehouseLotInventoryResponse> allLots = getLotInventory(warehouseId, productId, null, customThresholdDays, null);

        List<WarehouseLotInventoryResponse> alertLots = allLots.stream()
                .filter(l -> l.getBaseQuantity() != null && l.getBaseQuantity().compareTo(BigDecimal.ZERO) > 0)
                .filter(l -> Boolean.TRUE.equals(l.getIsExpired()) || Boolean.TRUE.equals(l.getIsExpiringSoon()))
                .sorted(Comparator.comparing(WarehouseLotInventoryResponse::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        int expiredCount = (int) alertLots.stream().filter(l -> Boolean.TRUE.equals(l.getIsExpired())).count();
        int expiringSoonCount = (int) alertLots.stream().filter(l -> Boolean.TRUE.equals(l.getIsExpiringSoon())).count();

        List<WarehouseLotInventoryResponse> filteredItems = alertLots;
        if ("EXPIRED".equalsIgnoreCase(status)) {
            filteredItems = alertLots.stream().filter(l -> Boolean.TRUE.equals(l.getIsExpired())).collect(Collectors.toList());
        } else if ("EXPIRING_SOON".equalsIgnoreCase(status)) {
            filteredItems = alertLots.stream().filter(l -> Boolean.TRUE.equals(l.getIsExpiringSoon())).collect(Collectors.toList());
        }

        return LotExpiryAlertResponse.builder()
                .totalAlerts(alertLots.size())
                .expiredCount(expiredCount)
                .expiringSoonCount(expiringSoonCount)
                .defaultThresholdDays(customThresholdDays != null ? customThresholdDays : 30)
                .items(filteredItems)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getLotManagementOptions() {
        requireGrant("WAREHOUSE_VIEW", "WAREHOUSE_MANAGE");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> grants = auth.getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.toSet());
        boolean admin = grants.contains("ROLE_ADMIN");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warehouses", warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .filter(w -> mayAccessWarehouse(w.getId())).map(w -> Map.of("id", w.getId(), "code", w.getCode(), "name", w.getName())).toList());
        result.put("products", productRepository.findAll().stream().map(p -> LotThresholdResponse.builder()
                .productId(p.getId()).productCode(p.getCode()).productName(p.getName()).unit(p.getUnit())
                .manageLot(Boolean.TRUE.equals(p.getManageLot())).expiryWarningDays(p.getExpiryWarningDays())
                .effectiveThresholdDays(p.getExpiryWarningDays() != null && p.getExpiryWarningDays() > 0 ? p.getExpiryWarningDays() : 30).build()).toList());
        result.put("statuses", List.of(Map.of("value", "ACTIVE", "label", "Còn hạn"),
                Map.of("value", "EXPIRING_SOON", "label", "Sắp hết hạn"), Map.of("value", "EXPIRED", "label", "Đã quá hạn")));
        result.put("canConfigure", admin || grants.contains("WAREHOUSE_MANAGE") || grants.contains("PRODUCT_EDIT"));
        result.put("canExport", admin || grants.contains("WAREHOUSE_EXPORT"));
        result.put("canOverrideExpiredLot", canOverrideExpiredLot());
        result.put("minimumThresholdDays", 1);
        result.put("maximumThresholdDays", Integer.MAX_VALUE);
        result.put("asOfDate", LocalDate.now());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LotThresholdResponse> getLotThresholds(String keyword) {
        List<Product> products;
        if (keyword != null && !keyword.isBlank()) {
            products = productRepository.findByCodeContainingIgnoreCaseOrNameContainingIgnoreCase(keyword.trim(), keyword.trim());
        } else {
            products = productRepository.findAll();
        }
        return products.stream()
                .filter(p -> Boolean.TRUE.equals(p.getManageLot()))
                .map(p -> LotThresholdResponse.builder()
                        .productId(p.getId())
                        .productCode(p.getCode())
                        .productName(p.getName())
                        .unit(p.getUnit())
                        .manageLot(p.getManageLot())
                        .expiryWarningDays(p.getExpiryWarningDays())
                        .effectiveThresholdDays(p.getExpiryWarningDays() != null && p.getExpiryWarningDays() > 0 ? p.getExpiryWarningDays() : 30)
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public LotThresholdResponse updateLotThreshold(Long productId, Integer thresholdDays, String username) {
        if (thresholdDays == null || thresholdDays < 1) {
            throw new IllegalArgumentException("Ngưỡng cảnh báo phải lớn hơn hoặc bằng 1 ngày");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + productId));

        product.setExpiryWarningDays(thresholdDays);
        productRepository.save(product);

        auditLogService.logAction(
                "LOT_THRESHOLD",
                product.getCode(),
                "UPDATE_THRESHOLD",
                null,
                "Cập nhật ngưỡng cảnh báo hạn dùng: " + thresholdDays + " ngày",
                username != null ? username : "system"
        );

        return LotThresholdResponse.builder()
                .productId(product.getId())
                .productCode(product.getCode())
                .productName(product.getName())
                .unit(product.getUnit())
                .manageLot(product.getManageLot())
                .expiryWarningDays(product.getExpiryWarningDays())
                .effectiveThresholdDays(product.getExpiryWarningDays())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public LotSuggestionResponse suggestLotForExport(Long warehouseId, Long productId, BigDecimal quantity, String unit) {
        requireGrant("WAREHOUSE_EXPORT", "WAREHOUSE_VIEW");
        if (warehouseId == null) {
            throw new IllegalArgumentException("Vui lòng chỉ định kho cần xuất hàng");
        }
        requireWarehouse(warehouseId);
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + warehouseId));
        if (!Boolean.TRUE.equals(warehouse.getActive())) {
            throw new IllegalArgumentException("Kho đã ngừng hoạt động");
        }

        if (productId == null) {
            throw new IllegalArgumentException("Vui lòng chỉ định sản phẩm cần xuất");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + productId));
        if (!Boolean.TRUE.equals(product.getActive())) {
            throw new IllegalArgumentException("Sản phẩm '" + product.getName() + "' đã ngừng kinh doanh");
        }

        boolean isLotManaged = Boolean.TRUE.equals(product.getManageLot())
                || lotInventoryRepository.existsByWarehouseIdAndProductIdAndBaseQuantityGreaterThan(warehouseId, productId, BigDecimal.ZERO);
        BigDecimal reqQty = quantity != null ? quantity : BigDecimal.ONE;
        if (reqQty.signum() <= 0 || reqQty.stripTrailingZeros().scale() > 4
                || reqQty.compareTo(new BigDecimal("99999999999.9999")) > 0) {
            throw new IllegalArgumentException("Số lượng phải lớn hơn 0, tối đa 11 chữ số nguyên và 4 chữ số thập phân");
        }
        String reqUnit = (unit != null && !unit.isBlank()) ? unit.trim() : product.getUnit();
        BigDecimal factor = unitConversionService.getConversionFactor(product, reqUnit);
        BigDecimal reqBaseQty = reqQty.multiply(factor);

        if (!isLotManaged) {
            return LotSuggestionResponse.builder()
                    .warehouseId(warehouse.getId())
                    .warehouseCode(warehouse.getCode())
                    .warehouseName(warehouse.getName())
                    .productId(product.getId())
                    .productCode(product.getCode())
                    .productName(product.getName())
                    .manageLot(false)
                    .requestedQuantity(reqQty)
                    .requestedUnit(reqUnit)
                    .requestedBaseQuantity(reqBaseQty)
                    .baseUnit(product.getUnit())
                    .suggestedLot(null)
                    .availableLots(Collections.emptyList())
                    .hasSufficientStock(true)
                    .message("Sản phẩm không áp dụng quản lý theo lô")
                    .build();
        }

        List<WarehouseLotInventory> allLots = lotInventoryRepository.findByWarehouseIdAndProductId(warehouseId, productId);
        LocalDate today = LocalDate.now();

        Comparator<WarehouseLotInventory> fefoComparator = (l1, l2) -> {
            boolean exp1 = l1.getExpiryDate() != null && l1.getExpiryDate().isBefore(today);
            boolean exp2 = l2.getExpiryDate() != null && l2.getExpiryDate().isBefore(today);
            if (exp1 != exp2) {
                return exp1 ? 1 : -1;
            }
            if (l1.getExpiryDate() != null && l2.getExpiryDate() != null) {
                int c = l1.getExpiryDate().compareTo(l2.getExpiryDate());
                if (c != 0) return c;
            } else if (l1.getExpiryDate() != null) {
                return -1;
            } else if (l2.getExpiryDate() != null) {
                return 1;
            }
            if (l1.getManufacturingDate() != null && l2.getManufacturingDate() != null) {
                int c = l1.getManufacturingDate().compareTo(l2.getManufacturingDate());
                if (c != 0) return c;
            } else if (l1.getManufacturingDate() != null) {
                return -1;
            } else if (l2.getManufacturingDate() != null) {
                return 1;
            }
            return Long.compare(l1.getId() != null ? l1.getId() : 0L, l2.getId() != null ? l2.getId() : 0L);
        };

        List<WarehouseLotInventory> activeLots = allLots.stream()
                .filter(l -> l.getBaseQuantity() != null && l.getBaseQuantity().compareTo(BigDecimal.ZERO) > 0)
                .sorted(fefoComparator)
                .toList();

        List<LotSuggestionResponse.SuggestedLotInfo> availableLotInfos = activeLots.stream().map(l -> {
            boolean isExp = l.getExpiryDate() != null && l.getExpiryDate().isBefore(today);
            Long daysUntil = l.getExpiryDate() != null ? java.time.temporal.ChronoUnit.DAYS.between(today, l.getExpiryDate()) : null;
            boolean sufficient = l.getBaseQuantity() != null && l.getBaseQuantity().compareTo(reqBaseQty) >= 0;
            return LotSuggestionResponse.SuggestedLotInfo.builder()
                    .lotId(l.getId())
                    .lotNumber(l.getLotNumber())
                    .expiryDate(l.getExpiryDate())
                    .manufacturingDate(l.getManufacturingDate())
                    .availableQuantity(l.getBaseQuantity())
                    .baseUnit(l.getBaseUnit())
                    .isExpired(isExp)
                    .daysUntilExpiry(daysUntil)
                    .sufficientForRequest(sufficient)
                    .build();
        }).toList();

        WarehouseLotInventory suggestedLot = chooseFefoLot(activeLots, reqBaseQty);

        LotSuggestionResponse.SuggestedLotInfo suggestedInfo = null;
        boolean hasSufficientStock = false;
        String message;

        if (suggestedLot != null) {
            hasSufficientStock = true;
            boolean isExp = suggestedLot.getExpiryDate() != null && suggestedLot.getExpiryDate().isBefore(today);
            Long daysUntil = suggestedLot.getExpiryDate() != null ? java.time.temporal.ChronoUnit.DAYS.between(today, suggestedLot.getExpiryDate()) : null;
            suggestedInfo = LotSuggestionResponse.SuggestedLotInfo.builder()
                    .lotId(suggestedLot.getId())
                    .lotNumber(suggestedLot.getLotNumber())
                    .expiryDate(suggestedLot.getExpiryDate())
                    .manufacturingDate(suggestedLot.getManufacturingDate())
                    .availableQuantity(suggestedLot.getBaseQuantity())
                    .baseUnit(suggestedLot.getBaseUnit())
                    .isExpired(isExp)
                    .daysUntilExpiry(daysUntil)
                    .sufficientForRequest(true)
                    .build();
            message = "Gợi ý xuất lô '" + suggestedLot.getLotNumber() + "' (hạn dùng gần nhất còn đủ số lượng)";
        } else if (activeLots.isEmpty()) {
            message = "Sản phẩm hiện không còn lô nào có tồn kho tại kho này";
        } else {
            BigDecimal totalStock = activeLots.stream()
                    .map(WarehouseLotInventory::getBaseQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            message = "Không có lô đơn lẻ nào còn đủ số lượng yêu cầu (" + reqBaseQty + " " + product.getUnit() +
                    ") và còn hạn sử dụng. Tổng tồn vật lý trên " + activeLots.size() + " lô là " + totalStock + " " + product.getUnit() +
                    ". Hãy giảm số lượng hoặc xuất từng lô; lô quá hạn/thiếu hạn dùng không được tự gợi ý.";
        }

        return LotSuggestionResponse.builder()
                .warehouseId(warehouse.getId())
                .warehouseCode(warehouse.getCode())
                .warehouseName(warehouse.getName())
                .productId(product.getId())
                .productCode(product.getCode())
                .productName(product.getName())
                .manageLot(true)
                .requestedQuantity(reqQty)
                .requestedUnit(reqUnit)
                .requestedBaseQuantity(reqBaseQty)
                .baseUnit(product.getUnit())
                .suggestedLot(suggestedInfo)
                .availableLots(availableLotInfos)
                .hasSufficientStock(hasSufficientStock)
                .message(message)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LotSuggestionResponse> suggestLotsForExport(BatchLotSuggestionRequest request) {
        if (request == null || request.getWarehouseId() == null) {
            throw new IllegalArgumentException("Vui lòng chỉ định kho xuất hàng");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Danh sách sản phẩm yêu cầu gợi ý không được để trống");
        }
        requireWarehouse(request.getWarehouseId());

        List<LotSuggestionResponse> results = new ArrayList<>();
        for (WarehouseVoucherItemRequest item : request.getItems()) {
            Long prodId = item.getProductId();
            if (prodId == null && item.getProductCode() != null && !item.getProductCode().isBlank()) {
                Product p = productRepository.findByCode(item.getProductCode().trim()).orElse(null);
                if (p != null) prodId = p.getId();
            }
            if (prodId == null) {
                throw new IllegalArgumentException("Thiếu thông tin sản phẩm trong danh sách gợi ý lô");
            }
            results.add(suggestLotForExport(request.getWarehouseId(), prodId, item.getQuantity(), item.getUnit()));
        }
        return results;
    }

    private WarehouseLotInventory findFefoSuggestedLot(Long warehouseId, Long productId, BigDecimal requiredBaseQuantity) {
        List<WarehouseLotInventory> lots = lotInventoryRepository.findByWarehouseIdAndProductId(warehouseId, productId);
        return chooseFefoLot(lots, requiredBaseQuantity);
    }

    private Optional<WarehouseLotInventory> findExportLot(List<WarehouseLotInventory> lockedLots, Long warehouseId, Long productId, String lotNumber) {
        return lockedLots == null
                ? lotInventoryRepository.findByWarehouseIdAndProductIdAndLotNumber(warehouseId, productId, lotNumber)
                : lockedLots.stream().filter(l -> Objects.equals(l.getLotNumber(), lotNumber)).findFirst();
    }

    private WarehouseLotInventory chooseFefoLot(List<WarehouseLotInventory> lots, BigDecimal requiredBaseQuantity) {
        if (lots == null || lots.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        Comparator<WarehouseLotInventory> fefoComparator = (l1, l2) -> {
            boolean exp1 = l1.getExpiryDate() != null && l1.getExpiryDate().isBefore(today);
            boolean exp2 = l2.getExpiryDate() != null && l2.getExpiryDate().isBefore(today);
            if (exp1 != exp2) {
                return exp1 ? 1 : -1;
            }
            if (l1.getExpiryDate() != null && l2.getExpiryDate() != null) {
                int c = l1.getExpiryDate().compareTo(l2.getExpiryDate());
                if (c != 0) return c;
            } else if (l1.getExpiryDate() != null) {
                return -1;
            } else if (l2.getExpiryDate() != null) {
                return 1;
            }
            if (l1.getManufacturingDate() != null && l2.getManufacturingDate() != null) {
                int c = l1.getManufacturingDate().compareTo(l2.getManufacturingDate());
                if (c != 0) return c;
            } else if (l1.getManufacturingDate() != null) {
                return -1;
            } else if (l2.getManufacturingDate() != null) {
                return 1;
            }
            return Long.compare(l1.getId() != null ? l1.getId() : 0L, l2.getId() != null ? l2.getId() : 0L);
        };

        return lots.stream()
                .filter(l -> l.getExpiryDate() != null && !l.getExpiryDate().isBefore(today))
                .filter(l -> l.getBaseQuantity() != null && l.getBaseQuantity().compareTo(BigDecimal.ZERO) > 0)
                .filter(l -> requiredBaseQuantity == null || l.getBaseQuantity().compareTo(requiredBaseQuantity) >= 0)
                .min(fefoComparator)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseInventoryResponse> getInventory(Long warehouseId) {
        List<WarehouseInventory> list;
        if (warehouseId != null) {
            list = inventoryRepository.findByWarehouseId(warehouseId);
        } else {
            list = inventoryRepository.findAll();
        }

        return list.stream().map(this::mapToInventoryResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseVoucherResponse> getVouchers(Long warehouseId, WarehouseVoucherType type) {
        return getVouchers(warehouseId, type, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseVoucherResponse> getVouchers(Long warehouseId, WarehouseVoucherType type, String status, Long supplierId) {
        requireGrant("WAREHOUSE_VIEW");
        if (warehouseId != null) requireWarehouse(warehouseId);
        List<WarehouseVoucher> vouchers = voucherRepository.searchVouchers(warehouseId, type, status, supplierId);
        return vouchers.stream().filter(v -> mayAccessWarehouse(v.getWarehouse().getId()))
                .map(this::mapToVoucherResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseVoucherResponse getVoucherById(Long id) {
        requireGrant("WAREHOUSE_VIEW");
        WarehouseVoucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho với ID: " + id));
        requireWarehouse(voucher.getWarehouse().getId());
        return mapToVoucherResponse(voucher);
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER & MAPPING METHODS
    // ═══════════════════════════════════════════════════════════════

    private void validateImportHeader(Supplier supplier, String documentNumber, LocalDateTime date) {
        if (supplier == null || !Boolean.TRUE.equals(supplier.getActive())) throw new IllegalArgumentException("Vui lòng chọn nhà cung cấp đang hoạt động");
        if (documentNumber == null || documentNumber.isBlank()) throw new IllegalArgumentException("Vui lòng nhập số chứng từ");
        if (date == null) throw new IllegalArgumentException("Vui lòng chọn ngày nhập kho");
    }

    private void requireSameLotDates(WarehouseLotInventory lot, LocalDate expiry, LocalDate manufacturing) {
        if (!Objects.equals(lot.getExpiryDate(), expiry) || (manufacturing != null && !Objects.equals(lot.getManufacturingDate(), manufacturing))) {
            throw new IllegalArgumentException("Số lô đã có ngày sản xuất/hạn sử dụng khác. Vui lòng kiểm tra lại lô hàng.");
        }
    }

    private Warehouse resolveWarehouse(Long warehouseId) {
        if (warehouseId != null) {
            Warehouse warehouse = warehouseRepository.findById(warehouseId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + warehouseId));
            if (!Boolean.TRUE.equals(warehouse.getActive())) {
                throw new IllegalArgumentException("Kho đã ngừng hoạt động");
            }
            return warehouse;
        }
        List<Warehouse> warehouses = warehouseRepository.findAllByActiveTrueOrderByNameAsc();
        if (warehouses.isEmpty()) {
            throw new IllegalArgumentException("Chưa có kho đang hoạt động. Vui lòng cấu hình kho thực tế trước khi ghi phiếu.");
        }
        if (warehouses.size() > 1) {
            throw new IllegalArgumentException("Vui lòng chọn kho (warehouseId) để ghi phiếu");
        }
        return warehouses.get(0);
    }

    private Supplier resolveSupplier(Long supplierId, String supplierCode) {
        if (supplierId != null) {
            return supplierRepository.findById(supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp với ID: " + supplierId));
        }
        if (supplierCode != null && !supplierCode.isBlank()) {
            return supplierRepository.findByCode(supplierCode.trim())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp với mã: " + supplierCode));
        }
        return null;
    }

    private Product resolveProduct(WarehouseVoucherItemRequest itemReq) {
        return resolveProduct(itemReq.getProductId(), itemReq.getProductCode());
    }



    private List<WarehouseVoucherItemRequest> normalizeItemRequests(WarehouseVoucherRequest request, WarehouseVoucherType type) {
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            return request.getItems();
        }
        // Trường hợp payload đơn giản {"productId": 1, "quantity": 50, "unit": "thùng"}
        if (request.getProductId() != null) {
            BigDecimal qty = request.getQuantity();
            if (qty == null && type == WarehouseVoucherType.ADJUSTMENT && request.getDelta() != null) {
                qty = request.getDelta().abs();
            }
            if (qty == null) {
                throw new IllegalArgumentException("Số lượng không được để trống");
            }
            return List.of(WarehouseVoucherItemRequest.builder()
                    .productId(request.getProductId())
                    .quantity(qty)
                    .unit(request.getUnit())
                    .build());
        }
        return Collections.emptyList();
    }

    private WarehouseResponse mapToWarehouseResponse(Warehouse warehouse) {
        int locCount = (int) warehouseLocationRepository.countByWarehouseId(warehouse.getId());
        int defaultDealerCount = (int) dealerRepository.countByWarehouseId(warehouse.getId());

        User manager = warehouse.getManager();
        Long managerUserId = manager != null ? manager.getId() : null;
        String managerUsername = manager != null ? manager.getUsername() : null;
        String managerFullName = manager != null ? manager.getFullName() : null;

        String managerName = warehouse.getManagerName();
        if ((managerName == null || managerName.isBlank()) && managerFullName != null) {
            managerName = managerFullName;
        }

        return WarehouseResponse.builder()
                .id(warehouse.getId())
                .code(warehouse.getCode())
                .name(warehouse.getName())
                .address(warehouse.getAddress())
                .managerUserId(managerUserId)
                .managerUsername(managerUsername)
                .managerFullName(managerFullName)
                .managerName(managerName)
                .active(warehouse.getActive())
                .defective(warehouse.isDefectiveWarehouse())
                .locationCount(locCount)
                .defaultDealerCount(defaultDealerCount)
                .createdAt(warehouse.getCreatedAt())
                .updatedAt(warehouse.getUpdatedAt())
                .build();
    }

    private WarehouseLocationResponse mapToLocationResponse(WarehouseLocation loc) {
        String parentCode = loc.getParent() != null ? loc.getParent().getCode() : null;
        String parentName = loc.getParent() != null ? loc.getParent().getName() : null;
        Long parentId = loc.getParent() != null ? loc.getParent().getId() : null;

        String fullDisplay = (loc.getParent() != null)
                ? loc.getParent().getName() + " > " + loc.getName()
                : loc.getName();

        int productCount = (int) locationProductRepository.countByLocationId(loc.getId());

        return WarehouseLocationResponse.builder()
                .id(loc.getId())
                .warehouseId(loc.getWarehouse() != null ? loc.getWarehouse().getId() : null)
                .warehouseCode(loc.getWarehouse() != null ? loc.getWarehouse().getCode() : null)
                .warehouseName(loc.getWarehouse() != null ? loc.getWarehouse().getName() : null)
                .code(loc.getCode())
                .name(loc.getName())
                .locationType(loc.getLocationType())
                .locationTypeDisplayName(loc.getLocationType() != null ? loc.getLocationType().getDisplayName() : null)
                .parentLocationId(parentId)
                .parentLocationCode(parentCode)
                .parentLocationName(parentName)
                .description(loc.getDescription())
                .pickingSequence(loc.getPickingSequence())
                .active(loc.getActive())
                .fullDisplayName(fullDisplay)
                .productCount(productCount)
                .createdAt(loc.getCreatedAt())
                .updatedAt(loc.getUpdatedAt())
                .build();
    }

    private ProductLocationResponse mapToProductLocationResponse(WarehouseLocationProduct wlp) {
        WarehouseLocation loc = wlp.getLocation();
        Product p = wlp.getProduct();

        String parentZoneCode = loc.getParent() != null ? loc.getParent().getCode() : null;
        String parentZoneName = loc.getParent() != null ? loc.getParent().getName() : null;
        Long parentZoneId = loc.getParent() != null ? loc.getParent().getId() : null;

        String locDisplay = (loc.getParent() != null)
                ? loc.getParent().getName() + " > " + loc.getName()
                : loc.getName();

        return ProductLocationResponse.builder()
                .id(wlp.getId())
                .warehouseId(loc.getWarehouse() != null ? loc.getWarehouse().getId() : null)
                .warehouseCode(loc.getWarehouse() != null ? loc.getWarehouse().getCode() : null)
                .warehouseName(loc.getWarehouse() != null ? loc.getWarehouse().getName() : null)
                .locationId(loc.getId())
                .locationCode(loc.getCode())
                .locationName(loc.getName())
                .locationType(loc.getLocationType())
                .locationTypeDisplayName(loc.getLocationType() != null ? loc.getLocationType().getDisplayName() : null)
                .parentZoneId(parentZoneId)
                .parentZoneCode(parentZoneCode)
                .parentZoneName(parentZoneName)
                .locationDisplay(locDisplay)
                .productId(p != null ? p.getId() : null)
                .productCode(p != null ? p.getCode() : null)
                .productName(p != null ? p.getName() : null)
                .baseUnit(p != null ? p.getUnit() : null)
                .isDefault(wlp.getIsDefault())
                .note(wlp.getNote())
                .build();
    }

    private WarehouseVoucherResponse mapToVoucherResponse(WarehouseVoucher voucher) {
        List<WarehouseVoucherItemResponse> items = voucher.getItems() != null
                ? voucher.getItems().stream().map(this::mapToVoucherItemResponse).collect(Collectors.toList())
                : Collections.emptyList();

        return WarehouseVoucherResponse.builder()
                .id(voucher.getId())
                .version(voucher.getVersion())
                .voucherCode(voucher.getVoucherCode())
                .documentNumber(voucher.getDocumentNumber())
                .voucherDate(voucher.getVoucherDate())
                .type(voucher.getType())
                .warehouseId(voucher.getWarehouse() != null ? voucher.getWarehouse().getId() : null)
                .warehouseCode(voucher.getWarehouse() != null ? voucher.getWarehouse().getCode() : null)
                .warehouseName(voucher.getWarehouse() != null ? voucher.getWarehouse().getName() : null)
                .supplierId(voucher.getSupplier() != null ? voucher.getSupplier().getId() : null)
                .supplierCode(voucher.getSupplier() != null ? voucher.getSupplier().getCode() : null)
                .supplierName(voucher.getSupplier() != null ? voucher.getSupplier().getName() : null)
                .status(voucher.getStatus())
                .referenceVoucherId(voucher.getReferenceVoucherId())
                .referenceVoucherCode(voucher.getReferenceVoucherCode())
                .adjustmentReason(voucher.getAdjustmentReason())
                .overrideExpiredLot(voucher.getOverrideExpiredLot())
                .expiredLotOverrideReason(voucher.getExpiredLotOverrideReason())
                .note(voucher.getNote())
                .createdBy(voucher.getCreatedBy())
                .recordedAt(voucher.getRecordedAt())
                .items(items)
                .build();
    }

    private WarehouseVoucherItemResponse mapToVoucherItemResponse(WarehouseVoucherItem item) {
        return WarehouseVoucherItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                .productCode(item.getProduct() != null ? item.getProduct().getCode() : null)
                .productName(item.getProduct() != null ? item.getProduct().getName() : null)
                .manageLot(item.getProduct() != null ? Boolean.TRUE.equals(item.getProduct().getManageLot()) : false)
                .unit(item.getUnit())
                .quantity(item.getQuantity())
                .conversionFactor(item.getConversionFactor())
                .baseUnit(item.getBaseUnit())
                .baseQuantity(item.getBaseQuantity())
                .price(item.getPrice())
                .totalAmount(item.getTotalAmount())
                .lotNumber(item.getLotNumber())
                .expiryDate(item.getExpiryDate())
                .manufacturingDate(item.getManufacturingDate())
                .suggestedLotNumber(item.getSuggestedLotNumber())
                .overrideReason(item.getOverrideReason())
                .overrideExpiredLot(item.getOverrideExpiredLot())
                .expiredLotOverrideReason(item.getExpiredLotOverrideReason())
                .note(item.getNote())
                .build();
    }

    private WarehouseInventoryResponse mapToInventoryResponse(WarehouseInventory inv) {
        Product p = inv.getProduct();
        BigDecimal physical = inv.getBaseQuantity() != null ? inv.getBaseQuantity() : BigDecimal.ZERO;
        BigDecimal reserved = inv.getReservedQuantity() != null ? inv.getReservedQuantity() : BigDecimal.ZERO;
        BigDecimal available = physical.subtract(reserved).max(BigDecimal.ZERO);

        Map<String, BigDecimal> converted = new LinkedHashMap<>();
        Map<String, BigDecimal> convertedAvailable = new LinkedHashMap<>();

        // Luôn có đơn vị cơ sở
        converted.put(inv.getBaseUnit(), physical);
        convertedAvailable.put(inv.getBaseUnit(), available);

        // Tính tương đương theo các đơn vị quy đổi đã khai báo của SKU
        if (p != null) {
            List<UnitConversion> conversions = unitConversionRepository.findByProductIdOrderByConversionFactorAsc(p.getId());
            for (UnitConversion uc : conversions) {
                if (uc.getConversionFactor() != null && uc.getConversionFactor().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal qtyInUnit = physical.divide(uc.getConversionFactor(), 2, RoundingMode.HALF_UP);
                    converted.put(uc.getUnitName(), qtyInUnit);

                    BigDecimal availInUnit = available.divide(uc.getConversionFactor(), 0, RoundingMode.FLOOR);
                    convertedAvailable.put(uc.getUnitName(), availInUnit);
                }
            }
        }

        // Tìm vị trí kệ trong kho lưu trữ
        Long locationId = null;
        String locationCode = null;
        String locationName = null;
        String locationType = null;
        String zoneName = null;
        String shelfName = null;
        String locationDisplay = null;

        if (inv.getWarehouse() != null && p != null) {
            List<WarehouseLocationProduct> mappings =
                    locationProductRepository.findByWarehouseIdAndProductId(inv.getWarehouse().getId(), p.getId());
            if (!mappings.isEmpty()) {
                WarehouseLocation loc = mappings.get(0).getLocation();
                locationId = loc.getId();
                locationCode = loc.getCode();
                locationName = loc.getName();
                locationType = loc.getLocationType() != null ? loc.getLocationType().name() : null;
                if (loc.getParent() != null) {
                    zoneName = loc.getParent().getName();
                    shelfName = loc.getName();
                    locationDisplay = loc.getParent().getName() + " > " + loc.getName();
                } else if (loc.getLocationType() == LocationType.ZONE) {
                    zoneName = loc.getName();
                    locationDisplay = loc.getName();
                } else {
                    shelfName = loc.getName();
                    locationDisplay = loc.getName();
                }
            }
        }

        return WarehouseInventoryResponse.builder()
                .id(inv.getId())
                .warehouseId(inv.getWarehouse() != null ? inv.getWarehouse().getId() : null)
                .warehouseCode(inv.getWarehouse() != null ? inv.getWarehouse().getCode() : null)
                .warehouseName(inv.getWarehouse() != null ? inv.getWarehouse().getName() : null)
                .productId(p != null ? p.getId() : null)
                .productCode(p != null ? p.getCode() : null)
                .productName(p != null ? p.getName() : null)
                .baseQuantity(physical)
                .inTransitQuantity(inv.getInTransitQuantity() != null ? inv.getInTransitQuantity() : BigDecimal.ZERO)
                .baseUnit(inv.getBaseUnit())
                .reservedQuantity(reserved)
                .availableQuantity(available)
                .convertedViews(converted)
                .convertedAvailableViews(convertedAvailable)
                .locationId(locationId)
                .locationCode(locationCode)
                .locationName(locationName)
                .locationType(locationType)
                .zoneName(zoneName)
                .shelfName(shelfName)
                .locationDisplay(locationDisplay)
                .updatedAt(inv.getUpdatedAt())
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CHUYỂN KHO NỘI BỘ (S5-07)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public WarehouseTransferResponse createTransfer(WarehouseTransferRequest request, String username) {
        return transferService.create(request, username);
    }

    @Override
    public WarehouseTransferResponse receiveTransfer(Long id, WarehouseTransferReceiveRequest request, String username) {
        return transferService.receive(id, request, username);
    }

    @Override
    public WarehouseTransferResponse cancelTransfer(Long id, String reason, String username) {
        return transferService.cancel(id, reason, username);
    }

    @Override
    public List<WarehouseTransferResponse> getTransfers(Long from, Long to, WarehouseTransferStatus status) {
        return transferService.list(from, to, status);
    }

    @Override
    public WarehouseTransferResponse getTransferById(Long id) {
        return transferService.get(id);
    }

    @Override
    public WarehouseTransferOptionsResponse getTransferOptions() {
        return transferService.options();
    }

    @Override
    public org.springframework.data.domain.Page<WarehouseTransferStockResponse> getTransferStock(Long id, String keyword, int page, int size) {
        return transferService.stock(id, keyword, page, size);
    }

    @Override
    public org.springframework.data.domain.Page<WarehouseTransferResponse> searchTransfers(Long from, Long to,
            WarehouseTransferStatus status, String keyword, int page, int size) {
        return transferService.search(from, to, status, keyword, page, size);
    }

    private Product resolveProduct(Long productId, String productCode) {
        if (productId != null) {
            return productRepository.findById(productId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + productId));
        }
        if (productCode != null && !productCode.isBlank()) {
            return productRepository.findByCode(productCode.trim().toUpperCase())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với mã: " + productCode));
        }
        throw new IllegalArgumentException("Cần cung cấp productId hoặc productCode");
    }
}
