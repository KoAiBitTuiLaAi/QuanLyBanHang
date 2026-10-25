package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.request.BatchSalesTargetRequest;
import com.quanlybanhang.dto.request.SalesTargetRequest;
import com.quanlybanhang.dto.request.UpdateSalesTargetRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import com.quanlybanhang.service.SalesTargetService;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
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
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Triển khai nghiệp vụ Đặt chỉ tiêu doanh số và Theo dõi tiến độ theo thời gian thực (S8-04).
 *
 * Tiêu chí nghiệm thu:
 * 1. Đặt chỉ tiêu theo tháng cho từng nhân viên và từng khu vực.
 * 2. Nhân viên xem được tiến độ của chính mình theo thời gian thực.
 * 3. Chỉ tiêu đã qua kỳ thì khoá lại, sửa phải có lý do và được ghi nhật ký.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesTargetServiceImpl implements SalesTargetService {

    public static final String AUDIT_ENTITY_TYPE = "SALES_TARGET";

    private final SalesTargetRepository salesTargetRepository;
    private final UserRepository userRepository;
    private final TerritoryRepository territoryRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. KIỂM TRA MA TRẬN PHÂN QUYỀN DB & PHẠM VI DỮ LIỆU (RLS)
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

    private String resolveActor(String fallback) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return (fallback != null && !fallback.isBlank()) ? fallback.trim() : "system";
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
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    /**
     * Kiểm tra người dùng có bị giới hạn phạm vi dữ liệu chỉ xem tiến độ của chính mình hay không.
     * ROLE_SALES thuần túy (không có vai trò quản lý/kế toán/viewer/admin và không có ORDER_APPROVE)
     * chỉ được xem chỉ tiêu và tiến độ của chính mình.
     */
    private boolean isSalesSelfScopedOnly(Set<String> grants) {
        if (grants.contains("ROLE_ADMIN")
                || grants.contains("ROLE_SALES_MANAGER")
                || grants.contains("ROLE_ACCOUNTANT")
                || grants.contains("ROLE_VIEWER")
                || grants.contains("ORDER_APPROVE")) {
            return false;
        }
        return true;
    }

    private void checkReadScopeForTarget(SalesTarget target, Set<String> grants, User currentUser) {
        if (!isSalesSelfScopedOnly(grants)) {
            return;
        }
        if (currentUser == null) {
            throw new AccessDeniedException("Không xác định được tài khoản nhân viên hiện tại");
        }
        if (target.getUser() != null) {
            if (!target.getUser().getId().equals(currentUser.getId())) {
                throw new AccessDeniedException("Nhân viên kinh doanh chỉ được xem chỉ tiêu và tiến độ của chính mình");
            }
            return;
        }
        // Chỉ tiêu chung theo khu vực (TERRITORY): nhân viên được xem nếu khu vực thuộc địa bàn được gán
        if (target.getTerritory() != null && currentUser.getTerritories() != null) {
            boolean assignedTerritory = currentUser.getTerritories().stream()
                    .anyMatch(t -> Objects.equals(t.getId(), target.getTerritory().getId()));
            if (assignedTerritory) {
                return;
            }
        }
        throw new AccessDeniedException("Bạn không có quyền xem chỉ tiêu ngoài phạm vi được phân công");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. XỬ LÝ KỲ THÁNG, KHOÁ TỰ ĐỘNG & ĐẶT/SỬA CHỈ TIÊU
    // ═══════════════════════════════════════════════════════════════════════════

    private YearMonth parseYearMonth(Integer year, Integer month, String period, boolean defaultToCurrent) {
        if (period != null && !period.trim().isEmpty()) {
            String trimmed = period.trim();
            try {
                YearMonth ym = YearMonth.parse(trimmed);
                if (year != null && year != ym.getYear()) {
                    throw new IllegalArgumentException("Năm (" + year + ") không khớp với kỳ (" + trimmed + ")");
                }
                if (month != null && month != ym.getMonthValue()) {
                    throw new IllegalArgumentException("Tháng (" + month + ") không khớp với kỳ (" + trimmed + ")");
                }
                validateYearMonthRange(ym.getYear(), ym.getMonthValue());
                return ym;
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException("Định dạng kỳ không hợp lệ (yêu cầu YYYY-MM, ví dụ: 2026-10)");
            }
        }
        if (year != null && month != null) {
            validateYearMonthRange(year, month);
            return YearMonth.of(year, month);
        }
        if (year != null || month != null) {
            throw new IllegalArgumentException("Phải cung cấp đầy đủ cả năm (year) và tháng (month), hoặc chuỗi kỳ (period YYYY-MM)");
        }
        if (defaultToCurrent) {
            return YearMonth.now();
        }
        throw new IllegalArgumentException("Vui lòng chọn kỳ chỉ tiêu theo tháng (year + month hoặc period YYYY-MM)");
    }

    private void validateYearMonthRange(int year, int month) {
        if (year < 2000 || year > 2100) {
            throw new IllegalArgumentException("Năm chỉ tiêu phải nằm trong khoảng từ 2000 đến 2100");
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Tháng chỉ tiêu phải từ 1 đến 12");
        }
    }

    private SalesTargetScopeType resolveScopeType(User user, Territory territory) {
        if (user != null && territory != null) {
            return SalesTargetScopeType.EMPLOYEE_TERRITORY;
        }
        if (user != null) {
            return SalesTargetScopeType.EMPLOYEE;
        }
        if (territory != null) {
            return SalesTargetScopeType.TERRITORY;
        }
        throw new IllegalArgumentException("Phải chọn ít nhất nhân viên kinh doanh hoặc khu vực để đặt chỉ tiêu doanh số");
    }

    private User resolveAndValidateEmployee(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên với ID: " + userId));
        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new IllegalArgumentException("Tài khoản nhân viên '" + user.getUsername() + "' đang bị khoá hoặc ngừng hoạt động");
        }
        boolean hasSalesRole = user.getRoles() != null && user.getRoles().stream()
                .anyMatch(r -> r.getName() == ERole.ROLE_SALES || r.getName() == ERole.ROLE_SALES_MANAGER);
        if (!hasSalesRole) {
            throw new IllegalArgumentException("Chỉ có thể đặt chỉ tiêu doanh số cho nhân viên thuộc bộ phận kinh doanh (ROLE_SALES hoặc ROLE_SALES_MANAGER)");
        }
        return user;
    }

    private Territory resolveAndValidateTerritory(Long territoryId) {
        if (territoryId == null) {
            return null;
        }
        Territory territory = territoryRepository.findById(territoryId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực với ID: " + territoryId));
        if (!Boolean.TRUE.equals(territory.getActive())) {
            throw new IllegalArgumentException("Khu vực '" + territory.getName() + "' đang ngừng hoạt động");
        }
        return territory;
    }

    private void validateTargetAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Mục tiêu doanh số phải lớn hơn 0");
        }
    }

    private String generateTargetCode(int year, int month, User user, Territory territory) {
        String userPart = user != null ? "NV" + user.getId() : "ALL";
        String territoryPart = territory != null ? "KV" + territory.getId() : "ALL";
        String baseCode = String.format("CT-%04d%02d-%s-%s", year, month, userPart, territoryPart);
        if (!salesTargetRepository.existsByTargetCode(baseCode)) {
            return baseCode;
        }
        return baseCode + "-" + System.currentTimeMillis() % 100000;
    }

    private String formatTargetSnapshot(SalesTarget target) {
        String emp = target.getUser() != null
                ? (target.getUser().getUsername() + (target.getUser().getFullName() != null ? " (" + target.getUser().getFullName() + ")" : ""))
                : "Toàn bộ";
        String terr = target.getTerritory() != null
                ? (target.getTerritory().getCode() + " - " + target.getTerritory().getName())
                : "Toàn bộ";
        String amt = target.getTargetAmount() != null
                ? target.getTargetAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                : "0.00";
        return String.format("Kỳ: %s | Phạm vi: %s | Nhân viên: %s | Khu vực: %s | Chỉ tiêu: %s VNĐ | Khoá: %s",
                target.getPeriodString(),
                target.getScopeType() != null ? target.getScopeType().name() : "",
                emp,
                terr,
                amt,
                Boolean.TRUE.equals(target.getLocked()) ? "Đã khoá" : "Mở");
    }

    @Override
    public SalesTargetOptionsResponse getOptions() {
        requireAnyPermission("REPORT_SALES", "ORDER_APPROVE");
        Set<String> grants = currentAuthorities();
        boolean selfScoped = isSalesSelfScopedOnly(grants);
        User currentUser = getCurrentUser();

        List<SalesTargetOptionsResponse.EmployeeOption> employees;
        List<SalesTargetOptionsResponse.TerritoryOption> territories;

        if (selfScoped && currentUser != null) {
            employees = List.of(toEmployeeOption(currentUser));
            Set<Territory> userTerritories = currentUser.getTerritories() != null ? currentUser.getTerritories() : Set.of();
            territories = userTerritories.stream()
                    .filter(t -> Boolean.TRUE.equals(t.getActive()))
                    .sorted(Comparator.comparing(Territory::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(t -> SalesTargetOptionsResponse.TerritoryOption.builder()
                            .id(t.getId())
                            .code(t.getCode())
                            .name(t.getName())
                            .build())
                    .toList();
        } else {
            employees = userRepository.findAll().stream()
                    .filter(u -> Boolean.TRUE.equals(u.getActive()))
                    .filter(u -> u.getRoles() != null && u.getRoles().stream()
                            .anyMatch(r -> r.getName() == ERole.ROLE_SALES || r.getName() == ERole.ROLE_SALES_MANAGER))
                    .sorted(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER))
                    .map(this::toEmployeeOption)
                    .toList();

            territories = territoryRepository.findAllByActiveTrueOrderByNameAsc().stream()
                    .map(t -> SalesTargetOptionsResponse.TerritoryOption.builder()
                            .id(t.getId())
                            .code(t.getCode())
                            .name(t.getName())
                            .build())
                    .toList();
        }

        YearMonth now = YearMonth.now();
        return SalesTargetOptionsResponse.builder()
                .currentYear(now.getYear())
                .currentMonth(now.getMonthValue())
                .currentPeriod(now.toString())
                .canView(hasAnyPermission(grants, "REPORT_SALES", "ORDER_APPROVE"))
                .canManage(hasAnyPermission(grants, "ORDER_APPROVE"))
                .employees(employees)
                .territories(territories)
                .scopeTypes(Arrays.stream(SalesTargetScopeType.values())
                        .map(s -> new SalesTargetOptionsResponse.OptionItem(s.name(), s.getDisplayName()))
                        .toList())
                .progressStatuses(List.of(
                        new SalesTargetOptionsResponse.OptionItem("ACHIEVED", "Đã đạt chỉ tiêu"),
                        new SalesTargetOptionsResponse.OptionItem("ON_TRACK", "Đúng tiến độ"),
                        new SalesTargetOptionsResponse.OptionItem("BEHIND", "Chậm tiến độ"),
                        new SalesTargetOptionsResponse.OptionItem("AT_RISK", "Cần tăng tốc"),
                        new SalesTargetOptionsResponse.OptionItem("NOT_STARTED", "Chưa phát sinh doanh số")
                ))
                .build();
    }

    private SalesTargetOptionsResponse.EmployeeOption toEmployeeOption(User u) {
        List<Long> tIds = u.getTerritories() == null ? List.of()
                : u.getTerritories().stream().map(Territory::getId).sorted().toList();
        List<String> tNames = u.getTerritories() == null ? List.of()
                : u.getTerritories().stream().map(Territory::getName).sorted().toList();
        return SalesTargetOptionsResponse.EmployeeOption.builder()
                .id(u.getId())
                .username(u.getUsername())
                .fullName(u.getFullName() != null ? u.getFullName() : u.getUsername())
                .email(u.getEmail())
                .territoryIds(tIds)
                .territoryNames(tNames)
                .build();
    }

    @Override
    @Transactional
    public SalesTargetResponse createTarget(SalesTargetRequest request, String username) {
        requireAnyPermission("ORDER_APPROVE");
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu đặt chỉ tiêu không được để trống");
        }
        String actor = resolveActor(username);

        YearMonth ym = parseYearMonth(request.getYear(), request.getMonth(), request.getPeriod(), false);
        validateTargetAmount(request.getTargetAmount());

        User user = resolveAndValidateEmployee(request.getUserId());
        Territory territory = resolveAndValidateTerritory(request.getTerritoryId());
        SalesTargetScopeType scopeType = resolveScopeType(user, territory);

        Long uId = user != null ? user.getId() : null;
        Long tId = territory != null ? territory.getId() : null;
        String scopeKey = SalesTarget.buildScopeKey(ym.getYear(), ym.getMonthValue(), uId, tId);

        if (salesTargetRepository.existsByScopeKey(scopeKey)) {
            throw new IllegalArgumentException(String.format(
                    "Chỉ tiêu doanh số kỳ %s cho %s đã tồn tại. Vui lòng sử dụng chức năng cập nhật chỉ tiêu.",
                    ym, describeScope(user, territory)));
        }

        YearMonth currentYm = YearMonth.now();
        boolean isPast = ym.isBefore(currentYm);
        LocalDateTime now = LocalDateTime.now();

        SalesTarget entity = SalesTarget.builder()
                .targetCode(generateTargetCode(ym.getYear(), ym.getMonthValue(), user, territory))
                .scopeKey(scopeKey)
                .targetYear(ym.getYear())
                .targetMonth(ym.getMonthValue())
                .scopeType(scopeType)
                .user(user)
                .territory(territory)
                .targetAmount(request.getTargetAmount().setScale(2, RoundingMode.HALF_UP))
                .locked(isPast)
                .lockedAt(isPast ? now : null)
                .lockedBy(isPast ? "SYSTEM_PAST_PERIOD" : null)
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .lastModifyReason(request.getReason() != null && !request.getReason().isBlank() ? request.getReason().trim() : null)
                .createdBy(actor)
                .updatedBy(actor)
                .createdAt(now)
                .updatedAt(now)
                .build();

        SalesTarget saved = salesTargetRepository.save(entity);
        auditLogService.logAction(
                AUDIT_ENTITY_TYPE,
                saved.getId().toString(),
                "CREATE",
                null,
                formatTargetSnapshot(saved),
                saved.getLastModifyReason(),
                actor);

        PeriodOrderIndex orderIndex = loadPeriodOrderIndex(ym);
        return mapToResponse(saved, currentYm, orderIndex, true);
    }

    @Override
    @Transactional
    public List<SalesTargetResponse> upsertBatch(BatchSalesTargetRequest request, String username) {
        requireAnyPermission("ORDER_APPROVE");
        if (request == null || request.getTargets() == null || request.getTargets().isEmpty()) {
            throw new IllegalArgumentException("Danh sách chỉ tiêu doanh số không được để trống");
        }
        String actor = resolveActor(username);
        YearMonth currentYm = YearMonth.now();
        String batchReason = request.getReason() != null && !request.getReason().isBlank()
                ? request.getReason().trim() : null;

        List<SalesTarget> savedTargets = new ArrayList<>();
        Set<String> seenScopeKeysInRequest = new HashSet<>();

        for (SalesTargetRequest item : request.getTargets()) {
            if (item == null) {
                throw new IllegalArgumentException("Phần tử chỉ tiêu trong danh sách không hợp lệ");
            }
            Integer y = item.getYear() != null ? item.getYear() : request.getYear();
            Integer m = item.getMonth() != null ? item.getMonth() : request.getMonth();
            String p = (item.getPeriod() != null && !item.getPeriod().isBlank()) ? item.getPeriod() : request.getPeriod();

            YearMonth ym = parseYearMonth(y, m, p, false);
            validateTargetAmount(item.getTargetAmount());

            User user = resolveAndValidateEmployee(item.getUserId());
            Territory territory = resolveAndValidateTerritory(item.getTerritoryId());
            SalesTargetScopeType scopeType = resolveScopeType(user, territory);

            Long uId = user != null ? user.getId() : null;
            Long tId = territory != null ? territory.getId() : null;
            String scopeKey = SalesTarget.buildScopeKey(ym.getYear(), ym.getMonthValue(), uId, tId);

            if (!seenScopeKeysInRequest.add(scopeKey)) {
                throw new IllegalArgumentException("Danh sách đặt chỉ tiêu bị trùng lặp phạm vi: " + describeScope(user, territory) + " trong kỳ " + ym);
            }

            Optional<SalesTarget> existingOpt = salesTargetRepository.findByScopeKeyForUpdate(scopeKey);
            String effectiveReason = (item.getReason() != null && !item.getReason().isBlank())
                    ? item.getReason().trim() : batchReason;

            if (existingOpt.isPresent()) {
                SalesTarget existing = existingOpt.get();
                boolean effectivelyLocked = existing.isEffectiveLocked(currentYm);
                if (effectivelyLocked && (effectiveReason == null || effectiveReason.isBlank())) {
                    throw new IllegalArgumentException(String.format(
                            "Chỉ tiêu kỳ %s (%s) đã qua kỳ hoặc đã bị khoá. Khi sửa bắt buộc phải có lý do.",
                            existing.getPeriodString(), describeScope(user, territory)));
                }
                String oldSnapshot = formatTargetSnapshot(existing);
                existing.setTargetAmount(item.getTargetAmount().setScale(2, RoundingMode.HALF_UP));
                if (item.getNote() != null) {
                    existing.setNote(item.getNote().trim());
                }
                if (effectivelyLocked) {
                    ensureLockedState(existing, currentYm, actor);
                }
                if (effectiveReason != null) {
                    existing.setLastModifyReason(effectiveReason);
                }
                existing.setUpdatedBy(actor);
                SalesTarget saved = salesTargetRepository.save(existing);
                auditLogService.logAction(
                        AUDIT_ENTITY_TYPE,
                        saved.getId().toString(),
                        effectivelyLocked ? "UPDATE_LOCKED_TARGET" : "UPDATE",
                        oldSnapshot,
                        formatTargetSnapshot(saved),
                        effectiveReason,
                        actor);
                savedTargets.add(saved);
            } else {
                boolean isPast = ym.isBefore(currentYm);
                LocalDateTime now = LocalDateTime.now();
                SalesTarget created = SalesTarget.builder()
                        .targetCode(generateTargetCode(ym.getYear(), ym.getMonthValue(), user, territory))
                        .scopeKey(scopeKey)
                        .targetYear(ym.getYear())
                        .targetMonth(ym.getMonthValue())
                        .scopeType(scopeType)
                        .user(user)
                        .territory(territory)
                        .targetAmount(item.getTargetAmount().setScale(2, RoundingMode.HALF_UP))
                        .locked(isPast)
                        .lockedAt(isPast ? now : null)
                        .lockedBy(isPast ? "SYSTEM_PAST_PERIOD" : null)
                        .note(item.getNote() != null ? item.getNote().trim() : null)
                        .lastModifyReason(effectiveReason)
                        .createdBy(actor)
                        .updatedBy(actor)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                SalesTarget saved = salesTargetRepository.save(created);
                auditLogService.logAction(
                        AUDIT_ENTITY_TYPE,
                        saved.getId().toString(),
                        "CREATE",
                        null,
                        formatTargetSnapshot(saved),
                        effectiveReason,
                        actor);
                savedTargets.add(saved);
            }
        }

        Map<YearMonth, PeriodOrderIndex> orderIndexByMonth = new HashMap<>();
        List<SalesTargetResponse> responses = new ArrayList<>();
        for (SalesTarget t : savedTargets) {
            YearMonth ym = t.getYearMonth();
            PeriodOrderIndex idx = orderIndexByMonth.computeIfAbsent(ym, this::loadPeriodOrderIndex);
            responses.add(mapToResponse(t, currentYm, idx, true));
        }
        return responses;
    }

    @Override
    @Transactional
    public SalesTargetResponse updateTarget(Long id, UpdateSalesTargetRequest request, String username) {
        requireAnyPermission("ORDER_APPROVE");
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu cập nhật chỉ tiêu không được để trống");
        }
        validateTargetAmount(request.getTargetAmount());

        String actor = resolveActor(username);
        SalesTarget target = salesTargetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ tiêu doanh số với ID: " + id));

        YearMonth currentYm = YearMonth.now();
        boolean effectivelyLocked = target.isEffectiveLocked(currentYm);
        String trimmedReason = request.getReason() != null ? request.getReason().trim() : null;

        // Tiêu chí 3 S8-04: Chỉ tiêu đã qua kỳ thì khoá lại, sửa phải có lý do và được ghi nhật ký
        if (effectivelyLocked && (trimmedReason == null || trimmedReason.isEmpty())) {
            throw new IllegalArgumentException(String.format(
                    "Chỉ tiêu kỳ %s đã qua kỳ hoặc đang bị khoá. Khi chỉnh sửa bắt buộc phải nhập lý do.",
                    target.getPeriodString()));
        }

        String oldSnapshot = formatTargetSnapshot(target);

        if (effectivelyLocked) {
            ensureLockedState(target, currentYm, actor);
        }

        target.setTargetAmount(request.getTargetAmount().setScale(2, RoundingMode.HALF_UP));
        if (request.getNote() != null) {
            target.setNote(request.getNote().trim());
        }
        if (trimmedReason != null && !trimmedReason.isEmpty()) {
            target.setLastModifyReason(trimmedReason);
        }
        target.setUpdatedBy(actor);

        SalesTarget saved = salesTargetRepository.save(target);
        String action = effectivelyLocked ? "UPDATE_LOCKED_TARGET" : "UPDATE";
        auditLogService.logAction(
                AUDIT_ENTITY_TYPE,
                saved.getId().toString(),
                action,
                oldSnapshot,
                formatTargetSnapshot(saved),
                trimmedReason,
                actor);

        PeriodOrderIndex orderIndex = loadPeriodOrderIndex(saved.getYearMonth());
        return mapToResponse(saved, currentYm, orderIndex, true);
    }

    private void ensureLockedState(SalesTarget target, YearMonth currentYm, String actor) {
        if (!Boolean.TRUE.equals(target.getLocked())) {
            target.setLocked(true);
            target.setLockedAt(LocalDateTime.now());
            target.setLockedBy(target.isPastPeriod(currentYm) ? "SYSTEM_PAST_PERIOD" : actor);
        }
    }

    @Override
    @Transactional
    public SalesTargetResponse lockTarget(Long id, String reason, String username) {
        requireAnyPermission("ORDER_APPROVE");
        String actor = resolveActor(username);
        SalesTarget target = salesTargetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ tiêu doanh số với ID: " + id));

        String oldSnapshot = formatTargetSnapshot(target);
        target.setLocked(true);
        if (target.getLockedAt() == null) {
            target.setLockedAt(LocalDateTime.now());
        }
        target.setLockedBy(actor);
        target.setUpdatedBy(actor);

        String trimmedReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Khoá chỉ tiêu doanh số";
        SalesTarget saved = salesTargetRepository.save(target);
        auditLogService.logAction(
                AUDIT_ENTITY_TYPE,
                saved.getId().toString(),
                "LOCK",
                oldSnapshot,
                formatTargetSnapshot(saved),
                trimmedReason,
                actor);

        YearMonth currentYm = YearMonth.now();
        PeriodOrderIndex orderIndex = loadPeriodOrderIndex(saved.getYearMonth());
        return mapToResponse(saved, currentYm, orderIndex, true);
    }

    @Override
    @Transactional
    public int lockPastPeriodTargets(String username) {
        String actor = (username != null && !username.isBlank()) ? username.trim() : "SYSTEM_SCHEDULER";
        YearMonth currentYm = YearMonth.now();
        List<SalesTarget> unlockedPast = salesTargetRepository.findUnlockedPastTargets(
                currentYm.getYear(), currentYm.getMonthValue());
        if (unlockedPast.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (SalesTarget target : unlockedPast) {
            String oldSnapshot = formatTargetSnapshot(target);
            target.setLocked(true);
            target.setLockedAt(now);
            target.setLockedBy(actor);
            target.setUpdatedBy(actor);
            SalesTarget saved = salesTargetRepository.save(target);
            auditLogService.logAction(
                    AUDIT_ENTITY_TYPE,
                    saved.getId().toString(),
                    "AUTO_LOCK_PAST_PERIOD",
                    oldSnapshot,
                    formatTargetSnapshot(saved),
                    "Tự động khoá chỉ tiêu đã qua kỳ tháng " + saved.getPeriodString(),
                    actor);
        }
        return unlockedPast.size();
    }

    @Override
    @Transactional
    public void deleteTarget(Long id, String username) {
        requireAnyPermission("ORDER_APPROVE");
        String actor = resolveActor(username);
        SalesTarget target = salesTargetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ tiêu doanh số với ID: " + id));

        YearMonth currentYm = YearMonth.now();
        if (target.isEffectiveLocked(currentYm)) {
            throw new IllegalArgumentException("Chỉ tiêu kỳ " + target.getPeriodString() + " đã qua kỳ hoặc đang bị khoá, không được phép xoá");
        }

        String oldSnapshot = formatTargetSnapshot(target);
        salesTargetRepository.delete(target);
        auditLogService.logAction(
                AUDIT_ENTITY_TYPE,
                id.toString(),
                "DELETE",
                oldSnapshot,
                null,
                "Xoá chỉ tiêu chưa khoá",
                actor);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. TRA CỨU CHỈ TIÊU & TÍNH TIẾN ĐỘ THEO THỜI GIAN THỰC
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public SalesTargetResponse getById(Long id) {
        requireAnyPermission("REPORT_SALES", "ORDER_APPROVE");
        Set<String> grants = currentAuthorities();
        User currentUser = getCurrentUser();

        SalesTarget target = salesTargetRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ tiêu doanh số với ID: " + id));

        checkReadScopeForTarget(target, grants, currentUser);

        YearMonth currentYm = YearMonth.now();
        PeriodOrderIndex orderIndex = loadPeriodOrderIndex(target.getYearMonth());
        return mapToResponse(target, currentYm, orderIndex, true);
    }

    @Override
    public List<SalesTargetAuditLogResponse> getAuditLogs(Long id) {
        requireAnyPermission("REPORT_SALES", "ORDER_APPROVE");
        Set<String> grants = currentAuthorities();
        User currentUser = getCurrentUser();

        SalesTarget target = salesTargetRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chỉ tiêu doanh số với ID: " + id));

        checkReadScopeForTarget(target, grants, currentUser);
        return loadAuditLogsForTarget(id);
    }

    @Override
    public List<SalesTargetResponse> searchTargets(
            Integer year,
            Integer month,
            String period,
            Long userId,
            Long territoryId,
            SalesTargetScopeType scopeType,
            Boolean locked,
            String progressStatus,
            String keyword) {

        requireAnyPermission("REPORT_SALES", "ORDER_APPROVE");
        Set<String> grants = currentAuthorities();
        boolean selfScoped = isSalesSelfScopedOnly(grants);
        User currentUser = getCurrentUser();

        if (selfScoped) {
            if (currentUser == null) {
                throw new AccessDeniedException("Không xác định được tài khoản nhân viên hiện tại");
            }
            if (userId != null && !userId.equals(currentUser.getId())) {
                throw new AccessDeniedException("Nhân viên kinh doanh chỉ được xem chỉ tiêu và tiến độ của chính mình");
            }
        }

        YearMonth filterYm = null;
        if (year != null || month != null || (period != null && !period.isBlank())) {
            filterYm = parseYearMonth(year, month, period, false);
        }

        final YearMonth finalFilterYm = filterYm;
        final Long effectiveUserId = selfScoped && currentUser != null ? currentUser.getId() : userId;
        final Set<Long> allowedTerritoryIds = (selfScoped && currentUser != null && currentUser.getTerritories() != null)
                ? currentUser.getTerritories().stream().map(Territory::getId).collect(Collectors.toSet())
                : Set.of();

        Specification<SalesTarget> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (finalFilterYm != null) {
                predicates.add(cb.equal(root.get("targetYear"), finalFilterYm.getYear()));
                predicates.add(cb.equal(root.get("targetMonth"), finalFilterYm.getMonthValue()));
            }

            if (selfScoped) {
                Join<SalesTarget, User> userJoin = root.join("user", JoinType.LEFT);
                Join<SalesTarget, Territory> terrJoin = root.join("territory", JoinType.LEFT);
                Predicate ownTarget = cb.equal(userJoin.get("id"), effectiveUserId);
                if (!allowedTerritoryIds.isEmpty()) {
                    Predicate assignedTerritoryTarget = cb.and(
                            cb.isNull(userJoin.get("id")),
                            terrJoin.get("id").in(allowedTerritoryIds)
                    );
                    predicates.add(cb.or(ownTarget, assignedTerritoryTarget));
                } else {
                    predicates.add(ownTarget);
                }
            } else if (effectiveUserId != null) {
                Join<SalesTarget, User> userJoin = root.join("user", JoinType.INNER);
                predicates.add(cb.equal(userJoin.get("id"), effectiveUserId));
            }

            if (territoryId != null) {
                Join<SalesTarget, Territory> terrJoin = root.join("territory", JoinType.INNER);
                predicates.add(cb.equal(terrJoin.get("id"), territoryId));
            }

            if (scopeType != null) {
                predicates.add(cb.equal(root.get("scopeType"), scopeType));
            }

            if (keyword != null && !keyword.trim().isEmpty()) {
                String pattern = "%" + keyword.trim().toLowerCase() + "%";
                Join<SalesTarget, User> userJoin = root.join("user", JoinType.LEFT);
                Join<SalesTarget, Territory> terrJoin = root.join("territory", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("targetCode")), pattern),
                        cb.like(cb.lower(userJoin.get("username")), pattern),
                        cb.like(cb.lower(userJoin.get("fullName")), pattern),
                        cb.like(cb.lower(terrJoin.get("code")), pattern),
                        cb.like(cb.lower(terrJoin.get("name")), pattern)
                ));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        List<SalesTarget> entities = salesTargetRepository.findAll(
                spec,
                Sort.by(Sort.Direction.DESC, "targetYear")
                        .and(Sort.by(Sort.Direction.DESC, "targetMonth"))
                        .and(Sort.by(Sort.Direction.ASC, "scopeType"))
                        .and(Sort.by(Sort.Direction.ASC, "id")));

        YearMonth currentYm = YearMonth.now();
        Map<YearMonth, PeriodOrderIndex> orderIndexByMonth = new HashMap<>();

        List<SalesTargetResponse> result = new ArrayList<>();
        for (SalesTarget entity : entities) {
            boolean effLocked = entity.isEffectiveLocked(currentYm);
            if (locked != null && !locked.equals(effLocked)) {
                continue;
            }
            YearMonth ym = entity.getYearMonth();
            PeriodOrderIndex idx = orderIndexByMonth.computeIfAbsent(ym, this::loadPeriodOrderIndex);
            SalesTargetResponse resp = mapToResponse(entity, currentYm, idx, false);
            if (progressStatus != null && !progressStatus.trim().isEmpty()) {
                if (!progressStatus.trim().equalsIgnoreCase(resp.getProgressStatus())) {
                    continue;
                }
            }
            result.add(resp);
        }
        return result;
    }

    @Override
    public MySalesTargetProgressResponse getMyProgress(Integer year, Integer month, String period, String username) {
        requireAnyPermission("REPORT_SALES", "ORDER_APPROVE");
        String actor = resolveActor(username);
        User user = userRepository.findByUsername(actor)
                .orElseThrow(() -> new AccessDeniedException("Không tìm thấy tài khoản người dùng đang đăng nhập: " + actor));

        YearMonth ym = parseYearMonth(year, month, period, true);
        YearMonth currentYm = YearMonth.now();
        PeriodOrderIndex orderIndex = loadPeriodOrderIndex(ym);

        List<SalesTarget> userTargetEntities = salesTargetRepository.findByTargetYearAndTargetMonthAndUserId(
                ym.getYear(), ym.getMonthValue(), user.getId());
        List<SalesTargetResponse> personalTargets = userTargetEntities.stream()
                .map(t -> mapToResponse(t, currentYm, orderIndex, false))
                .toList();

        // Tính tổng mục tiêu cá nhân: nếu có chỉ tiêu tổng EMPLOYEE thì dùng nó; nếu chỉ có EMPLOYEE_TERRITORY thì cộng tổng
        Optional<SalesTarget> overallEmployeeTarget = userTargetEntities.stream()
                .filter(t -> t.getScopeType() == SalesTargetScopeType.EMPLOYEE)
                .findFirst();
        BigDecimal totalTargetAmount;
        if (overallEmployeeTarget.isPresent()) {
            totalTargetAmount = overallEmployeeTarget.get().getTargetAmount();
        } else {
            totalTargetAmount = userTargetEntities.stream()
                    .filter(t -> t.getScopeType() == SalesTargetScopeType.EMPLOYEE_TERRITORY)
                    .map(SalesTarget::getTargetAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        totalTargetAmount = totalTargetAmount.setScale(2, RoundingMode.HALF_UP);

        // Doanh số thực tế của nhân viên trong kỳ theo thời gian thực
        OrderBucket empBucket = orderIndex.getForEmployee(user.getId(), user.getUsername());
        BigDecimal actualAmount = empBucket.totalAmount.setScale(2, RoundingMode.HALF_UP);
        int orderCount = empBucket.orderCount;

        PacingMetrics pacing = computePacing(ym, currentYm, totalTargetAmount, actualAmount);

        // Các chỉ tiêu khu vực (TERRITORY) liên quan đến nhân viên
        Set<Long> relevantTerritoryIds = new LinkedHashSet<>();
        if (user.getTerritories() != null) {
            user.getTerritories().forEach(t -> relevantTerritoryIds.add(t.getId()));
        }
        userTargetEntities.stream()
                .filter(t -> t.getTerritory() != null)
                .forEach(t -> relevantTerritoryIds.add(t.getTerritory().getId()));
        empBucket.amountByTerritoryId.keySet().forEach(relevantTerritoryIds::add);

        List<SalesTarget> allTerritoryTargetsInMonth = salesTargetRepository.findByTargetYearAndTargetMonthAndScopeType(
                ym.getYear(), ym.getMonthValue(), SalesTargetScopeType.TERRITORY);
        Map<Long, SalesTarget> territoryTargetMap = allTerritoryTargetsInMonth.stream()
                .filter(t -> t.getTerritory() != null)
                .collect(Collectors.toMap(t -> t.getTerritory().getId(), t -> t, (a, b) -> a));

        List<SalesTargetResponse> territoryTargetResponses = allTerritoryTargetsInMonth.stream()
                .filter(t -> t.getTerritory() != null && relevantTerritoryIds.contains(t.getTerritory().getId()))
                .map(t -> mapToResponse(t, currentYm, orderIndex, false))
                .toList();

        Map<Long, SalesTarget> empTerrTargetMap = userTargetEntities.stream()
                .filter(t -> t.getScopeType() == SalesTargetScopeType.EMPLOYEE_TERRITORY && t.getTerritory() != null)
                .collect(Collectors.toMap(t -> t.getTerritory().getId(), t -> t, (a, b) -> a));

        List<MySalesTargetProgressResponse.TerritoryBreakdownItem> breakdown = new ArrayList<>();
        if (!relevantTerritoryIds.isEmpty()) {
            Map<Long, Territory> territoryById = territoryRepository.findAllById(relevantTerritoryIds).stream()
                    .collect(Collectors.toMap(Territory::getId, t -> t));
            for (Long tId : relevantTerritoryIds) {
                Territory terr = territoryById.get(tId);
                if (terr == null) continue;
                SalesTarget empTerrTarget = empTerrTargetMap.get(tId);
                SalesTarget terrTarget = territoryTargetMap.get(tId);

                BigDecimal empTargetAmt = empTerrTarget != null ? empTerrTarget.getTargetAmount() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                BigDecimal terrTargetAmt = terrTarget != null ? terrTarget.getTargetAmount() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                BigDecimal empActualAmt = empBucket.amountByTerritoryId.getOrDefault(tId, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                int empTerrOrders = empBucket.ordersByTerritoryId.getOrDefault(tId, 0);
                BigDecimal terrActualAmt = orderIndex.getForTerritory(tId).totalAmount.setScale(2, RoundingMode.HALF_UP);

                BigDecimal empRate = empTargetAmt.compareTo(BigDecimal.ZERO) > 0
                        ? empActualAmt.multiply(new BigDecimal("100")).divide(empTargetAmt, 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

                breakdown.add(MySalesTargetProgressResponse.TerritoryBreakdownItem.builder()
                        .territoryId(terr.getId())
                        .territoryCode(terr.getCode())
                        .territoryName(terr.getName())
                        .employeeTargetAmount(empTargetAmt)
                        .territoryTargetAmount(terrTargetAmt)
                        .employeeActualAmount(empActualAmt)
                        .territoryActualAmount(terrActualAmt)
                        .employeeCompletionRate(empRate)
                        .employeeOrderCount(empTerrOrders)
                        .build());
            }
        }

        return MySalesTargetProgressResponse.builder()
                .year(ym.getYear())
                .month(ym.getMonthValue())
                .period(ym.toString())
                .pastPeriod(ym.isBefore(currentYm))
                .currentPeriod(ym.equals(currentYm))
                .userId(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName() != null ? user.getFullName() : user.getUsername())
                .totalDaysInMonth(pacing.totalDaysInMonth)
                .elapsedDays(pacing.elapsedDays)
                .remainingDays(pacing.remainingDays)
                .timeElapsedPercent(pacing.timeElapsedPercent)
                .totalTargetAmount(totalTargetAmount)
                .actualAmount(actualAmount)
                .achievedAmount(actualAmount)
                .remainingAmount(pacing.remainingAmount)
                .overachievedAmount(pacing.overachievedAmount)
                .completionRate(pacing.completionRate)
                .progressPercent(pacing.completionRate)
                .expectedAmountToDate(pacing.expectedAmountToDate)
                .pacingVarianceAmount(pacing.pacingVarianceAmount)
                .requiredDailyRunRate(pacing.requiredDailyRunRate)
                .orderCount(orderCount)
                .progressStatus(pacing.progressStatus)
                .progressStatusLabel(pacing.progressStatusLabel)
                .personalTargets(personalTargets)
                .territoryTargets(territoryTargetResponses)
                .territoryBreakdown(breakdown)
                .build();
    }

    @Override
    public SalesTargetSummaryResponse getSummary(
            Integer year,
            Integer month,
            String period,
            Long userId,
            Long territoryId) {

        requireAnyPermission("REPORT_SALES", "ORDER_APPROVE");
        YearMonth ym = parseYearMonth(year, month, period, true);
        YearMonth currentYm = YearMonth.now();

        List<SalesTargetResponse> targets = searchTargets(
                ym.getYear(), ym.getMonthValue(), null, userId, territoryId, null, null, null, null);

        PeriodOrderIndex orderIndex = loadPeriodOrderIndex(ym);
        PacingMetrics periodTime = computePacing(ym, currentYm, BigDecimal.ZERO, BigDecimal.ZERO);

        int empCount = 0;
        int terrCount = 0;
        int empTerrCount = 0;
        int achievedCount = 0;
        int onTrackCount = 0;
        int behindCount = 0;
        int lockedCount = 0;

        BigDecimal sumTarget = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sumActual = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        List<SalesTargetSummaryResponse.EmployeeProgressSummary> empSummaries = new ArrayList<>();
        List<SalesTargetSummaryResponse.TerritoryProgressSummary> terrSummaries = new ArrayList<>();

        for (SalesTargetResponse t : targets) {
            if (t.getScopeType() == SalesTargetScopeType.EMPLOYEE) {
                empCount++;
                empSummaries.add(SalesTargetSummaryResponse.EmployeeProgressSummary.builder()
                        .userId(t.getUserId())
                        .username(t.getUsername())
                        .fullName(t.getUserFullName())
                        .targetAmount(t.getTargetAmount())
                        .actualAmount(t.getActualAmount())
                        .remainingAmount(t.getRemainingAmount())
                        .completionRate(t.getCompletionRate())
                        .expectedAmountToDate(t.getExpectedAmountToDate())
                        .pacingVarianceAmount(t.getPacingVarianceAmount())
                        .requiredDailyRunRate(t.getRequiredDailyRunRate())
                        .orderCount(t.getOrderCount())
                        .progressStatus(t.getProgressStatus())
                        .progressStatusLabel(t.getProgressStatusLabel())
                        .locked(t.getLocked())
                        .build());
            } else if (t.getScopeType() == SalesTargetScopeType.TERRITORY) {
                terrCount++;
                terrSummaries.add(SalesTargetSummaryResponse.TerritoryProgressSummary.builder()
                        .territoryId(t.getTerritoryId())
                        .territoryCode(t.getTerritoryCode())
                        .territoryName(t.getTerritoryName())
                        .targetAmount(t.getTargetAmount())
                        .actualAmount(t.getActualAmount())
                        .remainingAmount(t.getRemainingAmount())
                        .completionRate(t.getCompletionRate())
                        .expectedAmountToDate(t.getExpectedAmountToDate())
                        .pacingVarianceAmount(t.getPacingVarianceAmount())
                        .requiredDailyRunRate(t.getRequiredDailyRunRate())
                        .orderCount(t.getOrderCount())
                        .progressStatus(t.getProgressStatus())
                        .progressStatusLabel(t.getProgressStatusLabel())
                        .locked(t.getLocked())
                        .build());
            } else if (t.getScopeType() == SalesTargetScopeType.EMPLOYEE_TERRITORY) {
                empTerrCount++;
            }

            if (Boolean.TRUE.equals(t.getLocked())) {
                lockedCount++;
            }
            if ("ACHIEVED".equals(t.getProgressStatus())) {
                achievedCount++;
            } else if ("ON_TRACK".equals(t.getProgressStatus())) {
                onTrackCount++;
            } else {
                behindCount++;
            }

            sumTarget = sumTarget.add(t.getTargetAmount() != null ? t.getTargetAmount() : BigDecimal.ZERO);
            sumActual = sumActual.add(t.getActualAmount() != null ? t.getActualAmount() : BigDecimal.ZERO);
        }

        // Nếu chỉ có EMPLOYEE_TERRITORY mà chưa tạo dòng tổng EMPLOYEE, tổng hợp theo nhân viên từ EMPLOYEE_TERRITORY
        if (empSummaries.isEmpty() && empTerrCount > 0) {
            Map<Long, List<SalesTargetResponse>> byUser = targets.stream()
                    .filter(t -> t.getScopeType() == SalesTargetScopeType.EMPLOYEE_TERRITORY && t.getUserId() != null)
                    .collect(Collectors.groupingBy(SalesTargetResponse::getUserId, LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<Long, List<SalesTargetResponse>> entry : byUser.entrySet()) {
                List<SalesTargetResponse> list = entry.getValue();
                SalesTargetResponse first = list.get(0);
                BigDecimal uTarget = list.stream().map(SalesTargetResponse::getTargetAmount)
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
                OrderBucket bucket = orderIndex.getForEmployee(first.getUserId(), first.getUsername());
                BigDecimal uActual = bucket.totalAmount.setScale(2, RoundingMode.HALF_UP);
                PacingMetrics uPacing = computePacing(ym, currentYm, uTarget, uActual);
                empSummaries.add(SalesTargetSummaryResponse.EmployeeProgressSummary.builder()
                        .userId(first.getUserId())
                        .username(first.getUsername())
                        .fullName(first.getUserFullName())
                        .targetAmount(uTarget)
                        .actualAmount(uActual)
                        .remainingAmount(uPacing.remainingAmount)
                        .completionRate(uPacing.completionRate)
                        .expectedAmountToDate(uPacing.expectedAmountToDate)
                        .pacingVarianceAmount(uPacing.pacingVarianceAmount)
                        .requiredDailyRunRate(uPacing.requiredDailyRunRate)
                        .orderCount(bucket.orderCount)
                        .progressStatus(uPacing.progressStatus)
                        .progressStatusLabel(uPacing.progressStatusLabel)
                        .locked(first.getLocked())
                        .build());
            }
        }

        PacingMetrics overallPacing = computePacing(ym, currentYm, sumTarget, sumActual);

        return SalesTargetSummaryResponse.builder()
                .year(ym.getYear())
                .month(ym.getMonthValue())
                .period(ym.toString())
                .pastPeriod(ym.isBefore(currentYm))
                .currentPeriod(ym.equals(currentYm))
                .totalDaysInMonth(periodTime.totalDaysInMonth)
                .elapsedDays(periodTime.elapsedDays)
                .remainingDays(periodTime.remainingDays)
                .timeElapsedPercent(periodTime.timeElapsedPercent)
                .totalTargets(targets.size())
                .employeeTargetCount(empCount)
                .territoryTargetCount(terrCount)
                .employeeTerritoryTargetCount(empTerrCount)
                .achievedCount(achievedCount)
                .onTrackCount(onTrackCount)
                .behindCount(behindCount)
                .lockedCount(lockedCount)
                .totalTargetAmount(sumTarget)
                .totalActualAmount(sumActual)
                .totalRemainingAmount(overallPacing.remainingAmount)
                .overallCompletionRate(overallPacing.completionRate)
                .expectedAmountToDate(overallPacing.expectedAmountToDate)
                .pacingVarianceAmount(overallPacing.pacingVarianceAmount)
                .targets(targets)
                .employeeProgress(empSummaries)
                .territoryProgress(terrSummaries)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. BỘ TÍNH TOÁN DOANH SỐ & TIẾN ĐỘ THỜI GIAN THỰC (REAL-TIME ENGINE)
    // ═══════════════════════════════════════════════════════════════════════════

    private static class OrderBucket {
        BigDecimal totalAmount = BigDecimal.ZERO;
        int orderCount = 0;
        final Map<Long, BigDecimal> amountByTerritoryId = new HashMap<>();
        final Map<Long, Integer> ordersByTerritoryId = new HashMap<>();

        void add(BigDecimal amount, Long territoryId) {
            BigDecimal safe = amount != null ? amount : BigDecimal.ZERO;
            this.totalAmount = this.totalAmount.add(safe);
            this.orderCount++;
            if (territoryId != null) {
                this.amountByTerritoryId.merge(territoryId, safe, BigDecimal::add);
                this.ordersByTerritoryId.merge(territoryId, 1, Integer::sum);
            }
        }
    }

    private static class PeriodOrderIndex {
        final Map<Long, OrderBucket> byEmployeeId = new HashMap<>();
        final Map<String, OrderBucket> byUnassignedCreatorUsername = new HashMap<>();
        final Map<Long, OrderBucket> byTerritoryId = new HashMap<>();

        OrderBucket getForEmployee(Long userId, String username) {
            OrderBucket combined = new OrderBucket();
            if (userId != null && byEmployeeId.containsKey(userId)) {
                OrderBucket b1 = byEmployeeId.get(userId);
                combined.totalAmount = combined.totalAmount.add(b1.totalAmount);
                combined.orderCount += b1.orderCount;
                b1.amountByTerritoryId.forEach((k, v) -> combined.amountByTerritoryId.merge(k, v, BigDecimal::add));
                b1.ordersByTerritoryId.forEach((k, v) -> combined.ordersByTerritoryId.merge(k, v, Integer::sum));
            }
            if (username != null && !username.isBlank()) {
                OrderBucket b2 = byUnassignedCreatorUsername.get(username.trim().toLowerCase(Locale.ROOT));
                if (b2 != null) {
                    combined.totalAmount = combined.totalAmount.add(b2.totalAmount);
                    combined.orderCount += b2.orderCount;
                    b2.amountByTerritoryId.forEach((k, v) -> combined.amountByTerritoryId.merge(k, v, BigDecimal::add));
                    b2.ordersByTerritoryId.forEach((k, v) -> combined.ordersByTerritoryId.merge(k, v, Integer::sum));
                }
            }
            return combined;
        }

        OrderBucket getForTerritory(Long territoryId) {
            if (territoryId == null) {
                return new OrderBucket();
            }
            return byTerritoryId.getOrDefault(territoryId, new OrderBucket());
        }
    }

    private PeriodOrderIndex loadPeriodOrderIndex(YearMonth ym) {
        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.atEndOfMonth().atTime(LocalTime.MAX);
        List<Order> orders = salesTargetRepository.findValidOrdersInPeriod(
                OrderStatus.debtBearingStatuses(), start, end);

        PeriodOrderIndex index = new PeriodOrderIndex();
        for (Order order : orders) {
            BigDecimal amount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
            Dealer dealer = order.getDealer();
            User assignedUser = dealer != null ? dealer.getAssignedUser() : null;
            Territory territory = dealer != null ? dealer.getTerritory() : null;
            Long territoryId = territory != null ? territory.getId() : null;

            if (assignedUser != null && assignedUser.getId() != null) {
                index.byEmployeeId.computeIfAbsent(assignedUser.getId(), k -> new OrderBucket())
                        .add(amount, territoryId);
            } else if (order.getCreatedBy() != null && !order.getCreatedBy().isBlank()) {
                String creatorKey = order.getCreatedBy().trim().toLowerCase(Locale.ROOT);
                index.byUnassignedCreatorUsername.computeIfAbsent(creatorKey, k -> new OrderBucket())
                        .add(amount, territoryId);
            }

            if (territoryId != null) {
                index.byTerritoryId.computeIfAbsent(territoryId, k -> new OrderBucket())
                        .add(amount, territoryId);
            }
        }
        return index;
    }

    private static class PacingMetrics {
        int totalDaysInMonth;
        int elapsedDays;
        int remainingDays;
        BigDecimal timeElapsedPercent;
        BigDecimal remainingAmount;
        BigDecimal overachievedAmount;
        BigDecimal completionRate;
        BigDecimal expectedAmountToDate;
        BigDecimal pacingVarianceAmount;
        BigDecimal requiredDailyRunRate;
        String progressStatus;
        String progressStatusLabel;
    }

    private PacingMetrics computePacing(YearMonth targetYm, YearMonth currentYm, BigDecimal targetAmount, BigDecimal actualAmount) {
        PacingMetrics m = new PacingMetrics();
        BigDecimal safeTarget = targetAmount != null ? targetAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal safeActual = actualAmount != null ? actualAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        m.totalDaysInMonth = targetYm.lengthOfMonth();
        if (targetYm.isBefore(currentYm)) {
            m.elapsedDays = m.totalDaysInMonth;
        } else if (targetYm.isAfter(currentYm)) {
            m.elapsedDays = 0;
        } else {
            m.elapsedDays = LocalDate.now().getDayOfMonth();
        }
        m.remainingDays = Math.max(0, m.totalDaysInMonth - m.elapsedDays);

        m.timeElapsedPercent = BigDecimal.valueOf(m.elapsedDays)
                .multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(m.totalDaysInMonth), 2, RoundingMode.HALF_UP);

        m.remainingAmount = safeTarget.subtract(safeActual).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        m.overachievedAmount = safeActual.subtract(safeTarget).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        if (safeTarget.compareTo(BigDecimal.ZERO) > 0) {
            m.completionRate = safeActual.multiply(new BigDecimal("100"))
                    .divide(safeTarget, 2, RoundingMode.HALF_UP);
        } else {
            m.completionRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        m.expectedAmountToDate = safeTarget.multiply(BigDecimal.valueOf(m.elapsedDays))
                .divide(BigDecimal.valueOf(m.totalDaysInMonth), 2, RoundingMode.HALF_UP);

        m.pacingVarianceAmount = safeActual.subtract(m.expectedAmountToDate).setScale(2, RoundingMode.HALF_UP);

        if (m.remainingDays > 0) {
            m.requiredDailyRunRate = m.remainingAmount.divide(BigDecimal.valueOf(m.remainingDays), 2, RoundingMode.HALF_UP);
        } else {
            m.requiredDailyRunRate = m.remainingAmount;
        }

        if (safeTarget.compareTo(BigDecimal.ZERO) > 0 && safeActual.compareTo(safeTarget) >= 0) {
            m.progressStatus = "ACHIEVED";
            m.progressStatusLabel = "Đã đạt chỉ tiêu";
        } else if (safeActual.compareTo(BigDecimal.ZERO) == 0) {
            if (m.elapsedDays > 0) {
                m.progressStatus = "AT_RISK";
                m.progressStatusLabel = "Cần tăng tốc (chưa có doanh số)";
            } else {
                m.progressStatus = "NOT_STARTED";
                m.progressStatusLabel = "Chưa bắt đầu kỳ";
            }
        } else {
            BigDecimal threshold90 = m.expectedAmountToDate.multiply(new BigDecimal("0.90"));
            if (safeActual.compareTo(threshold90) >= 0) {
                m.progressStatus = "ON_TRACK";
                m.progressStatusLabel = "Đúng tiến độ";
            } else {
                m.progressStatus = "BEHIND";
                m.progressStatusLabel = "Chậm tiến độ";
            }
        }
        return m;
    }

    private SalesTargetResponse mapToResponse(
            SalesTarget entity,
            YearMonth currentYm,
            PeriodOrderIndex orderIndex,
            boolean includeAuditLogs) {

        YearMonth ym = entity.getYearMonth();
        User user = entity.getUser();
        Territory territory = entity.getTerritory();

        BigDecimal actualAmount = BigDecimal.ZERO;
        int orderCount = 0;

        if (entity.getScopeType() == SalesTargetScopeType.EMPLOYEE && user != null) {
            OrderBucket bucket = orderIndex.getForEmployee(user.getId(), user.getUsername());
            actualAmount = bucket.totalAmount;
            orderCount = bucket.orderCount;
        } else if (entity.getScopeType() == SalesTargetScopeType.TERRITORY && territory != null) {
            OrderBucket bucket = orderIndex.getForTerritory(territory.getId());
            actualAmount = bucket.totalAmount;
            orderCount = bucket.orderCount;
        } else if (entity.getScopeType() == SalesTargetScopeType.EMPLOYEE_TERRITORY && user != null && territory != null) {
            OrderBucket bucket = orderIndex.getForEmployee(user.getId(), user.getUsername());
            actualAmount = bucket.amountByTerritoryId.getOrDefault(territory.getId(), BigDecimal.ZERO);
            orderCount = bucket.ordersByTerritoryId.getOrDefault(territory.getId(), 0);
        }

        BigDecimal targetAmount = entity.getTargetAmount() != null
                ? entity.getTargetAmount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        actualAmount = actualAmount.setScale(2, RoundingMode.HALF_UP);

        PacingMetrics pacing = computePacing(ym, currentYm, targetAmount, actualAmount);

        boolean pastPeriod = ym.isBefore(currentYm);
        boolean currentPeriod = ym.equals(currentYm);
        boolean effectiveLocked = entity.isEffectiveLocked(currentYm);

        List<SalesTargetAuditLogResponse> logs = includeAuditLogs && entity.getId() != null
                ? loadAuditLogsForTarget(entity.getId())
                : null;

        return SalesTargetResponse.builder()
                .id(entity.getId())
                .version(entity.getVersion())
                .targetCode(entity.getTargetCode())
                .year(entity.getTargetYear())
                .month(entity.getTargetMonth())
                .period(entity.getPeriodString())
                .scopeType(entity.getScopeType())
                .scopeTypeLabel(entity.getScopeType() != null ? entity.getScopeType().getDisplayName() : null)
                .userId(user != null ? user.getId() : null)
                .username(user != null ? user.getUsername() : null)
                .userFullName(user != null ? (user.getFullName() != null ? user.getFullName() : user.getUsername()) : null)
                .territoryId(territory != null ? territory.getId() : null)
                .territoryCode(territory != null ? territory.getCode() : null)
                .territoryName(territory != null ? territory.getName() : null)
                .targetAmount(targetAmount)
                .actualAmount(actualAmount)
                .achievedAmount(actualAmount)
                .remainingAmount(pacing.remainingAmount)
                .overachievedAmount(pacing.overachievedAmount)
                .completionRate(pacing.completionRate)
                .progressPercent(pacing.completionRate)
                .orderCount(orderCount)
                .totalDaysInMonth(pacing.totalDaysInMonth)
                .elapsedDays(pacing.elapsedDays)
                .remainingDays(pacing.remainingDays)
                .timeElapsedPercent(pacing.timeElapsedPercent)
                .expectedAmountToDate(pacing.expectedAmountToDate)
                .pacingVarianceAmount(pacing.pacingVarianceAmount)
                .requiredDailyRunRate(pacing.requiredDailyRunRate)
                .progressStatus(pacing.progressStatus)
                .progressStatusLabel(pacing.progressStatusLabel)
                .pastPeriod(pastPeriod)
                .currentPeriod(currentPeriod)
                .locked(effectiveLocked)
                .lockedAt(entity.getLockedAt())
                .lockedBy(entity.getLockedBy() != null ? entity.getLockedBy() : (pastPeriod ? "SYSTEM_PAST_PERIOD" : null))
                .note(entity.getNote())
                .lastModifyReason(entity.getLastModifyReason())
                .createdBy(entity.getCreatedBy())
                .updatedBy(entity.getUpdatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .auditLogs(logs)
                .build();
    }

    private List<SalesTargetAuditLogResponse> loadAuditLogsForTarget(Long targetId) {
        if (targetId == null || auditLogRepository == null) {
            return List.of();
        }
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(AUDIT_ENTITY_TYPE, targetId.toString())
                .stream()
                .map(a -> SalesTargetAuditLogResponse.builder()
                        .id(a.getId())
                        .entityType(a.getEntityType())
                        .entityId(a.getEntityId())
                        .action(a.getAction())
                        .oldValue(a.getOldValue())
                        .newValue(a.getNewValue())
                        .reason(a.getReason())
                        .username(a.getUsername())
                        .createdAt(a.getCreatedAt())
                        .build())
                .toList();
    }

    private String describeScope(User user, Territory territory) {
        if (user != null && territory != null) {
            return "nhân viên '" + user.getUsername() + "' tại khu vực '" + territory.getCode() + "'";
        }
        if (user != null) {
            return "nhân viên '" + user.getUsername() + "'";
        }
        if (territory != null) {
            return "khu vực '" + territory.getCode() + "'";
        }
        return "toàn hệ thống";
    }
}

