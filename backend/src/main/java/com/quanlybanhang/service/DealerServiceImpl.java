package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.AssignDealerRequest;
import com.quanlybanhang.dto.request.BulkHandoverRequest;
import com.quanlybanhang.dto.request.DealerRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.exception.DealerHasTransactionsException;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DealerServiceImpl implements DealerService {

    private final DealerRepository dealerRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final TerritoryRepository territoryRepository;
    private final DealerAssignmentHistoryRepository dealerAssignmentHistoryRepository;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(readOnly = true)
    public Page<DealerResponse> getAllDealers(
            String keyword,
            CustomerGroup customerGroup,
            Long territoryId,
            Long assignedUserId,
            Boolean active,
            Boolean locked,
            Pageable pageable) {

        Long effectiveAssignedUserId = assignedUserId;
        if (isSalesOnlyUser()) {
            User currentUser = getCurrentUser();
            if (currentUser != null) {
                effectiveAssignedUserId = currentUser.getId();
            }
        }

        String trimmedKeyword = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        Page<Dealer> page = dealerRepository.searchDealers(
                trimmedKeyword,
                customerGroup,
                territoryId,
                effectiveAssignedUserId,
                active,
                locked,
                pageable);

        return page.map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DealerResponse> getActiveDealers() {
        if (isDealerUser()) {
            User currentUser = getCurrentUser();
            if (currentUser != null && currentUser.getDealer() != null) {
                Dealer dealer = currentUser.getDealer();
                if (!Boolean.TRUE.equals(dealer.getLocked()) && Boolean.TRUE.equals(dealer.getActive())) {
                    return List.of(mapToResponse(dealer));
                }
                return List.of();
            }
        }
        if (isSalesOnlyUser()) {
            User currentUser = getCurrentUser();
            if (currentUser != null) {
                return dealerRepository.findByAssignedUserIdAndActiveTrueOrderByNameAsc(currentUser.getId()).stream()
                        .filter(d -> !Boolean.TRUE.equals(d.getLocked()))
                        .map(this::mapToResponse)
                        .toList();
            }
        }
        return dealerRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(d -> !Boolean.TRUE.equals(d.getLocked()))
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DealerResponse getDealerById(Long id) {
        Dealer dealer = dealerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + id));

        // S4-10: Đại lý chỉ nhìn thấy thông tin của chính mình
        if (isDealerUser()) {
            User currentUser = getCurrentUser();
            if (currentUser != null) {
                if (currentUser.getDealer() == null || !currentUser.getDealer().getId().equals(id)) {
                    throw new AccessDeniedException("Bạn chỉ có quyền xem thông tin của chính đại lý mình");
                }
            }
        }

        // Quy tắc S3-06: Nhân viên kinh doanh chỉ nhìn thấy đại lý mình phụ trách
        if (isSalesOnlyUser()) {
            User currentUser = getCurrentUser();
            if (currentUser != null) {
                if (dealer.getAssignedUser() == null || !dealer.getAssignedUser().getId().equals(currentUser.getId())) {
                    throw new AccessDeniedException("Bạn chỉ có quyền xem đại lý do mình phụ trách");
                }
            }
        }

        return mapToResponse(dealer);
    }

    @Override
    @Transactional
    public DealerResponse createDealer(DealerRequest request, String currentUsername) {
        String code = request.getCode().trim().toUpperCase();
        if (dealerRepository.existsByCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("Mã đại lý '" + code + "' đã tồn tại");
        }

        User assignedUser = null;
        if (request.getAssignedUserId() != null) {
            assignedUser = userRepository.findById(request.getAssignedUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người phụ trách với ID: " + request.getAssignedUserId()));
            validateSalesUser(assignedUser);
        }

        Territory territory = null;
        if (request.getTerritoryId() != null) {
            territory = territoryRepository.findById(request.getTerritoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực với ID: " + request.getTerritoryId()));
        }

        Dealer dealer = Dealer.builder()
                .code(code)
                .name(request.getName().trim())
                .taxCode(request.getTaxCode() != null ? request.getTaxCode().trim() : null)
                .customerGroup(request.getCustomerGroup() != null ? request.getCustomerGroup() : CustomerGroup.DAI_LY_CAP_1)
                .assignedUser(assignedUser)
                .territory(territory)
                .active(request.getActive() != null ? request.getActive() : true)
                .handoverRequired(false)
                .debtLimit(request.getDebtLimit() != null ? request.getDebtLimit() : BigDecimal.ZERO)
                .maxDebtDays(request.getMaxDebtDays() != null ? request.getMaxDebtDays() : 0)
                .phone(request.getPhone() != null ? request.getPhone().trim() : null)
                .email(request.getEmail() != null ? request.getEmail().trim() : null)
                .address(request.getAddress() != null ? request.getAddress().trim() : null)
                .build();

        Dealer saved = dealerRepository.save(dealer);

        if (assignedUser != null) {
            recordAssignmentHistory(
                    saved,
                    null,
                    assignedUser,
                    saved.getTerritory(),
                    "INITIAL_ASSIGNMENT",
                    "Phân công người phụ trách khi khởi tạo hồ sơ đại lý",
                    currentUsername);
        }

        auditLogService.logAction("DEALER", saved.getId().toString(), "CREATE_DEALER", null, saved.getCode(), currentUsername);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public DealerResponse updateDealer(Long id, DealerRequest request, String currentUsername) {
        Dealer dealer = dealerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + id));

        String newCode = request.getCode().trim().toUpperCase();
        if (dealerRepository.existsByCodeIgnoreCaseAndIdNot(newCode, id)) {
            throw new IllegalArgumentException("Mã đại lý '" + newCode + "' đã tồn tại");
        }

        User oldAssignedUser = dealer.getAssignedUser();
        User newAssignedUser = null;
        if (request.getAssignedUserId() != null) {
            newAssignedUser = userRepository.findById(request.getAssignedUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người phụ trách với ID: " + request.getAssignedUserId()));
            validateSalesUser(newAssignedUser);
        }

        Territory territory = null;
        if (request.getTerritoryId() != null) {
            territory = territoryRepository.findById(request.getTerritoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực với ID: " + request.getTerritoryId()));
        }

        dealer.setCode(newCode);
        dealer.setName(request.getName().trim());
        dealer.setTaxCode(request.getTaxCode() != null ? request.getTaxCode().trim() : null);
        dealer.setCustomerGroup(request.getCustomerGroup() != null ? request.getCustomerGroup() : CustomerGroup.DAI_LY_CAP_1);
        dealer.setAssignedUser(newAssignedUser);
        dealer.setTerritory(territory);
        if (request.getActive() != null) {
            dealer.setActive(request.getActive());
        }
        if (request.getDebtLimit() != null) {
            dealer.setDebtLimit(request.getDebtLimit());
        }
        if (request.getMaxDebtDays() != null) {
            dealer.setMaxDebtDays(request.getMaxDebtDays());
        }
        dealer.setPhone(request.getPhone() != null ? request.getPhone().trim() : null);
        dealer.setEmail(request.getEmail() != null ? request.getEmail().trim() : null);
        dealer.setAddress(request.getAddress() != null ? request.getAddress().trim() : null);

        // Nếu có gán người phụ trách mới thì xóa cờ handoverRequired
        if (newAssignedUser != null && Boolean.TRUE.equals(dealer.getHandoverRequired())) {
            dealer.setHandoverRequired(false);
        }

        Dealer updated = dealerRepository.save(dealer);

        // Ghi lại lịch sử nếu có sự thay đổi người phụ trách
        if (isUserChanged(oldAssignedUser, newAssignedUser)) {
            recordAssignmentHistory(
                    updated,
                    oldAssignedUser,
                    newAssignedUser,
                    updated.getTerritory(),
                    "INDIVIDUAL_ASSIGNMENT",
                    "Cập nhật người phụ trách trong hồ sơ đại lý",
                    currentUsername);
        }

        auditLogService.logAction("DEALER", updated.getId().toString(), "UPDATE_DEALER", null, updated.getCode(), currentUsername);
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public DealerResponse updateStatus(Long id, boolean active, String currentUsername) {
        Dealer dealer = dealerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + id));

        dealer.setActive(active);
        Dealer saved = dealerRepository.save(dealer);
        auditLogService.logAction(
                "DEALER",
                saved.getId().toString(),
                active ? "ACTIVATE_DEALER" : "DEACTIVATE_DEALER",
                null,
                saved.getCode(),
                currentUsername);

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public DealerResponse lockDealer(Long id, String reason, String currentUsername) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Bắt buộc nhập lý do khoá");
        }
        String normalizedReason = reason.trim();
        if (normalizedReason.length() > 500) {
            throw new IllegalArgumentException("Lý do khoá tối đa 500 ký tự");
        }

        Dealer dealer = dealerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + id));

        dealer.setLocked(true);
        dealer.setLockReason(normalizedReason);
        dealer.setLockedAt(java.time.LocalDateTime.now());
        dealer.setLockedBy(currentUsername != null ? currentUsername : "system");

        Dealer saved = dealerRepository.save(dealer);
        auditLogService.logAction(
                "DEALER",
                saved.getId().toString(),
                "LOCK_DEALER",
                null,
                "Khoá giao dịch đại lý: " + saved.getCode() + ". Lý do: " + normalizedReason,
                currentUsername != null ? currentUsername : "system");

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public DealerResponse unlockDealer(Long id, String currentUsername) {
        Dealer dealer = dealerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + id));

        String oldReason = dealer.getLockReason();
        dealer.setLocked(false);
        dealer.setLockReason(null);
        dealer.setLockedAt(null);
        dealer.setLockedBy(null);

        Dealer saved = dealerRepository.save(dealer);
        auditLogService.logAction(
                "DEALER",
                saved.getId().toString(),
                "UNLOCK_DEALER",
                oldReason,
                "Mở khoá giao dịch cho đại lý: " + saved.getCode(),
                currentUsername != null ? currentUsername : "system");

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getInProgressOrders(Long dealerId) {
        if (isSalesOnlyUser()) {
            getDealerById(dealerId);
        } else if (!dealerRepository.existsById(dealerId)) {
            throw new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId);
        }
        return orderRepository.findByDealerIdAndStatusNotInOrderByCreatedAtDesc(
                        dealerId, List.of("CANCELLED", "REJECTED"))
                .stream()
                .map(this::mapOrderToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteDealer(Long id, String currentUsername) {
        Dealer dealer = dealerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + id));

        // Quy tắc S3-03: Đại lý đã phát sinh giao dịch thì không xoá được, chỉ ngừng giao dịch
        if (orderRepository.existsByDealerId(id)) {
            throw new DealerHasTransactionsException(dealer.getCode(), dealer.getName());
        }

        dealerRepository.delete(dealer);
        auditLogService.logAction("DEALER", id.toString(), "DELETE_DEALER", dealer.getCode(), null, currentUsername);
    }

    @Override
    @Transactional(readOnly = true)
    public DealerOptionsResponse getDealerOptions() {
        List<DealerOptionsResponse.CustomerGroupOption> customerGroups = Arrays.stream(CustomerGroup.values())
                .map(g -> new DealerOptionsResponse.CustomerGroupOption(g.name(), g.getDisplayName()))
                .toList();

        List<NamedResourceResponse> territories = territoryRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(t -> new NamedResourceResponse(t.getId(), t.getCode(), t.getName()))
                .toList();

        List<DealerOptionsResponse.AssignedUserOption> assignedUsers = userRepository.findAll().stream()
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .filter(this::hasSalesRole)
                .map(u -> new DealerOptionsResponse.AssignedUserOption(u.getId(), u.getUsername(), u.getFullName()))
                .toList();

        return DealerOptionsResponse.builder()
                .customerGroups(customerGroups)
                .territories(territories)
                .assignedUsers(assignedUsers)
                .build();
    }

    // ── Phân công & Chuyển giao địa bàn (S3-06) ──

    @Override
    @Transactional
    public DealerResponse assignDealer(Long id, AssignDealerRequest request, String currentUsername) {
        Dealer dealer = dealerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + id));

        User toUser = userRepository.findById(request.getAssignedUserId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người phụ trách với ID: " + request.getAssignedUserId()));

        validateSalesUser(toUser);

        User fromUser = dealer.getAssignedUser();
        dealer.setAssignedUser(toUser);
        dealer.setHandoverRequired(false);

        Dealer saved = dealerRepository.save(dealer);

        recordAssignmentHistory(
                saved,
                fromUser,
                toUser,
                saved.getTerritory(),
                "INDIVIDUAL_ASSIGNMENT",
                (request.getReason() != null && !request.getReason().isBlank())
                        ? request.getReason().trim()
                        : "Phân công nhân viên phụ trách chính",
                currentUsername);

        auditLogService.logAction(
                "DEALER_ASSIGN",
                saved.getId().toString(),
                "ASSIGN_DEALER",
                fromUser != null ? fromUser.getUsername() : "Chưa có",
                toUser.getUsername(),
                currentUsername);

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public BulkHandoverResponse bulkHandover(BulkHandoverRequest request, String currentUsername) {
        if (request.getFromUserId().equals(request.getToUserId())) {
            throw new IllegalArgumentException("Nhân viên tiếp nhận phải khác nhân viên bàn giao");
        }

        User fromUser = userRepository.findById(request.getFromUserId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên bàn giao"));

        User toUser = userRepository.findById(request.getToUserId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên tiếp nhận"));

        validateSalesUser(toUser);

        List<Dealer> candidates;
        if (request.getDealerIds() != null && !request.getDealerIds().isEmpty()) {
            candidates = dealerRepository.findByAssignedUserIdAndIdIn(fromUser.getId(), request.getDealerIds());
        } else if (request.getTerritoryId() != null) {
            candidates = dealerRepository.findByAssignedUserIdAndTerritoryId(fromUser.getId(), request.getTerritoryId());
        } else {
            candidates = dealerRepository.findByAssignedUserId(fromUser.getId());
        }

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy đại lý nào thuộc nhân viên bàn giao để chuyển giao");
        }

        User performedBy = userRepository.findByUsername(currentUsername).orElse(null);
        String performedByName = performedBy != null
                ? (performedBy.getFullName() != null ? performedBy.getFullName() : performedBy.getUsername())
                : currentUsername;
        String fromUserName = fromUser.getFullName() != null ? fromUser.getFullName() : fromUser.getUsername();
        String toUserName = toUser.getFullName() != null ? toUser.getFullName() : toUser.getUsername();
        LocalDateTime now = LocalDateTime.now();

        List<DealerAssignmentHistory> historyList = new ArrayList<>();
        List<Long> transferredDealerIds = new ArrayList<>();
        List<String> transferredDealerNames = new ArrayList<>();

        for (Dealer d : candidates) {
            d.setAssignedUser(toUser);
            d.setHandoverRequired(false);

            DealerAssignmentHistory h = DealerAssignmentHistory.builder()
                    .dealer(d)
                    .dealerCode(d.getCode())
                    .dealerName(d.getName())
                    .fromUser(fromUser)
                    .fromUserName(fromUserName)
                    .toUser(toUser)
                    .toUserName(toUserName)
                    .territory(d.getTerritory())
                    .territoryName(d.getTerritory() != null ? d.getTerritory().getName() : null)
                    .transferType("BULK_HANDOVER")
                    .reason(request.getReason().trim())
                    .performedBy(performedBy)
                    .performedByName(performedByName)
                    .transferredAt(now)
                    .build();
            historyList.add(h);

            transferredDealerIds.add(d.getId());
            transferredDealerNames.add(d.getName());
        }

        dealerRepository.saveAll(candidates);
        dealerAssignmentHistoryRepository.saveAll(historyList);

        auditLogService.logAction(
                "DEALER_HANDOVER",
                fromUser.getId() + "->" + toUser.getId(),
                "BULK_HANDOVER",
                "Chuyển " + candidates.size() + " đại lý từ " + fromUser.getUsername() + " sang " + toUser.getUsername(),
                request.getReason(),
                currentUsername);

        Territory territory = request.getTerritoryId() != null
                ? territoryRepository.findById(request.getTerritoryId()).orElse(null)
                : null;

        return BulkHandoverResponse.builder()
                .totalTransferred(candidates.size())
                .fromUserId(fromUser.getId())
                .fromUsername(fromUser.getUsername())
                .fromUserFullName(fromUserName)
                .toUserId(toUser.getId())
                .toUsername(toUser.getUsername())
                .toUserFullName(toUserName)
                .territoryId(territory != null ? territory.getId() : null)
                .territoryName(territory != null ? territory.getName() : null)
                .transferredDealerIds(transferredDealerIds)
                .transferredDealerNames(transferredDealerNames)
                .reason(request.getReason().trim())
                .transferredAt(now)
                .performedByUsername(currentUsername)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DealerResponse> getPendingHandoverDealers() {
        return dealerRepository.findByHandoverRequiredTrueOrderByNameAsc().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DealerAssignmentHistoryResponse> getHandoverHistory(
            Long fromUserId,
            Long toUserId,
            Long dealerId,
            Long territoryId,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable) {

        Page<DealerAssignmentHistory> page = dealerAssignmentHistoryRepository.searchHistory(
                fromUserId,
                toUserId,
                dealerId,
                territoryId,
                startDate,
                endDate,
                pageable);

        return page.map(this::mapToHistoryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DealerAssignmentHistoryResponse> getDealerAssignmentHistory(Long dealerId) {
        // Kiểm tra quyền nếu là SALES: chỉ xem được lịch sử đại lý do mình phụ trách
        if (isSalesOnlyUser()) {
            Dealer dealer = dealerRepository.findById(dealerId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));
            User currentUser = getCurrentUser();
            if (currentUser != null && (dealer.getAssignedUser() == null || !dealer.getAssignedUser().getId().equals(currentUser.getId()))) {
                throw new AccessDeniedException("Bạn chỉ có quyền xem đại lý do mình phụ trách");
            }
        }

        return dealerAssignmentHistoryRepository.findByDealerIdOrderByTransferredAtDesc(dealerId).stream()
                .map(this::mapToHistoryResponse)
                .toList();
    }

    // ── Helper methods ──

    private void validateSalesUser(User user) {
        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new IllegalArgumentException("Tài khoản '" + user.getUsername() + "' đang bị khoá, không thể phân công phụ trách đại lý");
        }
        if (!hasSalesRole(user)) {
            throw new IllegalArgumentException("Nhân viên được gán phải có vai trò kinh doanh (ROLE_SALES hoặc ROLE_SALES_MANAGER)");
        }
    }

    private boolean hasSalesRole(User user) {
        if (user.getRoles() == null) return false;
        return user.getRoles().stream()
                .anyMatch(r -> r.getName() == ERole.ROLE_SALES || r.getName() == ERole.ROLE_SALES_MANAGER);
    }

    private boolean isUserChanged(User oldUser, User newUser) {
        if (oldUser == null && newUser == null) return false;
        if (oldUser == null || newUser == null) return true;
        return !oldUser.getId().equals(newUser.getId());
    }

    private void recordAssignmentHistory(
            Dealer dealer,
            User fromUser,
            User toUser,
            Territory territory,
            String transferType,
            String reason,
            String currentUsername) {

        User performedBy = userRepository.findByUsername(currentUsername).orElse(null);
        String performedByName = performedBy != null
                ? (performedBy.getFullName() != null ? performedBy.getFullName() : performedBy.getUsername())
                : currentUsername;

        DealerAssignmentHistory history = DealerAssignmentHistory.builder()
                .dealer(dealer)
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .fromUser(fromUser)
                .fromUserName(fromUser != null ? (fromUser.getFullName() != null ? fromUser.getFullName() : fromUser.getUsername()) : null)
                .toUser(toUser)
                .toUserName(toUser != null ? (toUser.getFullName() != null ? toUser.getFullName() : toUser.getUsername()) : null)
                .territory(territory)
                .territoryName(territory != null ? territory.getName() : null)
                .transferType(transferType)
                .reason(reason)
                .performedBy(performedBy)
                .performedByName(performedByName)
                .transferredAt(LocalDateTime.now())
                .build();

        dealerAssignmentHistoryRepository.save(history);
    }

    private boolean isSalesOnlyUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        boolean hasSales = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SALES".equals(a.getAuthority()));
        boolean hasElevated = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_SALES_MANAGER".equals(a.getAuthority())
                        || "ROLE_ACCOUNTANT".equals(a.getAuthority())
                        || "ROLE_VIEWER".equals(a.getAuthority()));
        return hasSales && !hasElevated;
    }

    private boolean isDealerUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_DEALER".equals(a.getAuthority()));
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    private DealerResponse mapToResponse(Dealer dealer) {
        boolean hasTransactions = orderRepository.existsByDealerId(dealer.getId());
        long inProgressOrderCount = orderRepository.countByDealerIdAndStatusNotIn(
                dealer.getId(), List.of("CANCELLED", "REJECTED"));

        BigDecimal currentDebt = orderRepository.sumTotalAmountByDealerIdAndStatus(dealer.getId(), "RECORDED");
        if (currentDebt == null) {
            currentDebt = BigDecimal.ZERO;
        }
        BigDecimal debtLimit = dealer.getDebtLimit() != null ? dealer.getDebtLimit() : BigDecimal.ZERO;
        BigDecimal remainingDebtLimit = debtLimit.subtract(currentDebt);
        int maxDebtDays = dealer.getMaxDebtDays() != null ? dealer.getMaxDebtDays() : 0;

        boolean hasOverdueDebt = false;
        int maxOverdueDays = 0;
        if (maxDebtDays > 0) {
            List<Order> recordedOrders = orderRepository.findByDealerIdAndStatusOrderByRecordedAtAsc(dealer.getId(), "RECORDED");
            LocalDate today = LocalDate.now();
            for (Order o : recordedOrders) {
                LocalDateTime recTime = o.getRecordedAt() != null ? o.getRecordedAt() : o.getCreatedAt();
                if (recTime != null) {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(recTime.toLocalDate(), today);
                    if (days > maxDebtDays) {
                        hasOverdueDebt = true;
                        int diff = (int) (days - maxDebtDays);
                        if (diff > maxOverdueDays) {
                            maxOverdueDays = diff;
                        }
                    }
                }
            }
        }

        return DealerResponse.builder()
                .id(dealer.getId())
                .code(dealer.getCode())
                .name(dealer.getName())
                .taxCode(dealer.getTaxCode())
                .customerGroup(dealer.getCustomerGroup())
                .customerGroupDisplayName(dealer.getCustomerGroup() != null ? dealer.getCustomerGroup().getDisplayName() : null)
                .territoryId(dealer.getTerritory() != null ? dealer.getTerritory().getId() : null)
                .territoryCode(dealer.getTerritory() != null ? dealer.getTerritory().getCode() : null)
                .territoryName(dealer.getTerritory() != null ? dealer.getTerritory().getName() : null)
                .assignedUserId(dealer.getAssignedUser() != null ? dealer.getAssignedUser().getId() : null)
                .assignedUsername(dealer.getAssignedUser() != null ? dealer.getAssignedUser().getUsername() : null)
                .assignedUserFullName(dealer.getAssignedUser() != null ? dealer.getAssignedUser().getFullName() : null)
                .active(dealer.getActive())
                .handoverRequired(dealer.getHandoverRequired())
                .debtLimit(dealer.getDebtLimit())
                .maxDebtDays(dealer.getMaxDebtDays() != null ? dealer.getMaxDebtDays() : 0)
                .currentDebt(currentDebt)
                .remainingDebtLimit(remainingDebtLimit)
                .hasOverdueDebt(hasOverdueDebt)
                .isBlockedDueToDebt(hasOverdueDebt || Boolean.TRUE.equals(dealer.getLocked()))
                .maxOverdueDays(maxOverdueDays)
                .locked(dealer.getLocked())
                .lockReason(dealer.getLockReason())
                .lockedAt(dealer.getLockedAt())
                .lockedBy(dealer.getLockedBy())
                .inProgressOrderCount(inProgressOrderCount)
                .phone(dealer.getPhone())
                .email(dealer.getEmail())
                .address(dealer.getAddress())
                .hasTransactions(hasTransactions)
                .createdAt(dealer.getCreatedAt())
                .updatedAt(dealer.getUpdatedAt())
                .build();
    }

    private OrderResponse mapOrderToResponse(Order order) {
        List<OrderItemResponse> items = order.getItems() != null
                ? order.getItems().stream().map(this::mapOrderItemToResponse).toList()
                : java.util.Collections.emptyList();

        Dealer dealer = order.getDealer();
        boolean dealerLocked = dealer != null && Boolean.TRUE.equals(dealer.getLocked());
        String dealerLockWarning = dealerLocked
                ? String.format("Cảnh báo: Đại lý '%s' (%s) đang bị khoá giao dịch. Lý do: %s. Đơn hàng đang dở dang vẫn được xử lý nhưng cần theo dõi thu hồi công nợ.",
                        dealer.getName(), dealer.getCode(), dealer.getLockReason() != null ? dealer.getLockReason() : "Không nêu rõ")
                : null;

        return OrderResponse.builder()
                .id(order.getId())
                .orderCode(order.getOrderCode())
                .customerName(order.getCustomerName())
                .customerGroup(order.getCustomerGroup())
                .dealerId(order.getDealer() != null ? order.getDealer().getId() : null)
                .deliveryPointId(order.getDeliveryPoint() != null ? order.getDeliveryPoint().getId() : null)
                .expectedDeliveryDate(order.getExpectedDeliveryDate())
                .priceListId(order.getPriceList() != null ? order.getPriceList().getId() : null)
                .priceListCode(order.getPriceList() != null ? order.getPriceList().getCode() : null)
                .status(order.getStatus())
                .requiresApproval(order.getRequiresApproval())
                .approvalReason(order.getApprovalReason())
                .approvedBy(order.getApprovedBy())
                .approvedAt(order.getApprovedAt())
                .totalAmount(order.getTotalAmount())
                .note(order.getNote())
                .createdBy(order.getCreatedBy())
                .createdAt(order.getCreatedAt())
                .recordedAt(order.getRecordedAt())
                .items(items)
                .dealerLocked(dealerLocked)
                .dealerLockWarning(dealerLockWarning)
                .warning(dealerLockWarning)
                .build();
    }

    private OrderItemResponse mapOrderItemToResponse(OrderItem item) {
        return OrderItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                .productCode(item.getProduct() != null ? item.getProduct().getCode() : null)
                .productName(item.getProduct() != null ? item.getProduct().getName() : null)
                .unit(item.getUnit())
                .quantity(item.getQuantity())
                .conversionFactor(item.getConversionFactor())
                .baseUnit(item.getBaseUnit())
                .baseQuantity(item.getBaseQuantity())
                .price(item.getPrice())
                .floorPrice(item.getFloorPrice())
                .belowFloorPrice(item.getBelowFloorPrice())
                .totalAmount(item.getTotalAmount())
                .note(item.getNote())
                .build();
    }

    private DealerAssignmentHistoryResponse mapToHistoryResponse(DealerAssignmentHistory history) {
        return DealerAssignmentHistoryResponse.builder()
                .id(history.getId())
                .dealerId(history.getDealer().getId())
                .dealerCode(history.getDealerCode())
                .dealerName(history.getDealerName())
                .fromUserId(history.getFromUser() != null ? history.getFromUser().getId() : null)
                .fromUsername(history.getFromUser() != null ? history.getFromUser().getUsername() : null)
                .fromUserFullName(history.getFromUserName())
                .toUserId(history.getToUser() != null ? history.getToUser().getId() : null)
                .toUsername(history.getToUser() != null ? history.getToUser().getUsername() : null)
                .toUserFullName(history.getToUserName())
                .territoryId(history.getTerritory() != null ? history.getTerritory().getId() : null)
                .territoryName(history.getTerritoryName())
                .transferType(history.getTransferType())
                .reason(history.getReason())
                .performedByUserId(history.getPerformedBy() != null ? history.getPerformedBy().getId() : null)
                .performedByUsername(history.getPerformedBy() != null ? history.getPerformedBy().getUsername() : null)
                .performedByFullName(history.getPerformedByName())
                .transferredAt(history.getTransferredAt())
                .build();
    }
}
