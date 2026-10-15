package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.AdminUserRequest;
import com.quanlybanhang.dto.response.NamedResourceResponse;
import com.quanlybanhang.dto.response.UserImportResponse;
import com.quanlybanhang.dto.response.UserImportRowResponse;
import com.quanlybanhang.dto.response.UserResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AdminUserService {
    private static final Set<ERole> WAREHOUSE_ROLES = EnumSet.of(
            ERole.ROLE_WAREHOUSE_MANAGER, ERole.ROLE_WAREHOUSE_STAFF);
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern VIETNAMESE_PHONE_PATTERN = Pattern.compile("^(0|\\+84|84)[35789]\\d{8}$");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WarehouseRepository warehouseRepository;
    private final TerritoryRepository territoryRepository;
    private final DealerRepository dealerRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountMailService accountMailService;

    @Transactional(readOnly = true)
    public Page<UserResponse> search(String keyword, ERole role, Boolean active, Pageable pageable) {
        Specification<User> spec = Specification.where(null);
        if (keyword != null && !keyword.isBlank()) {
            String term = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("fullName")), term),
                    cb.like(cb.lower(root.get("username")), term),
                    cb.like(cb.lower(root.get("phone")), term)));
        }
        if (active != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        if (role != null) {
            spec = spec.and((root, query, cb) -> {
                query.distinct(true);
                return cb.equal(root.join("roles", JoinType.INNER).get("name"), role);
            });
        }
        return userRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return toResponse(findUser(id));
    }

    @Transactional(readOnly = true)
    public UserResponse getByUsername(String username) {
        return toResponse(userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng")));
    }

    @Transactional
    public UserResponse create(AdminUserRequest request) {
        validateDuplicates(request.getUsername(), request.getEmail(), null);
        Set<Role> roles = resolveRoles(request.getRoles());
        Set<Warehouse> warehouses = resolveWarehouses(request.getWarehouseIds());
        validateWarehouseAssignment(roles, warehouses);
        Set<Territory> territories = resolveTerritories(request.getTerritoryIds());
        Dealer dealer = null;
        if (request.getDealerId() != null) {
            dealer = dealerRepository.findById(request.getDealerId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + request.getDealerId()));
        }

        String temporaryPassword = generateTemporaryPassword();
        User user = User.builder()
                .username(request.getUsername().trim())
                .email(request.getEmail().trim().toLowerCase(Locale.ROOT))
                .fullName(request.getFullName().trim())
                .phone(trimToNull(request.getPhone()))
                .password(passwordEncoder.encode(temporaryPassword))
                .roles(roles)
                .warehouses(warehouses)
                .territories(territories)
                .dealer(dealer)
                .mustChangePassword(true)
                .active(true)
                .build();
        userRepository.save(user);
        accountMailService.sendActivation(user, temporaryPassword);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public UserImportResponse previewImport(List<AdminUserRequest> requests) {
        List<AdminUserRequest> safeRequests = requests == null ? List.of() : requests;
        Set<String> usernamesInFile = new HashSet<>();
        Set<String> emailsInFile = new HashSet<>();
        List<UserImportRowResponse> rows = new ArrayList<>();

        for (int index = 0; index < safeRequests.size(); index++) {
            AdminUserRequest request = safeRequests.get(index);
            List<String> errors = validateImportRow(request, usernamesInFile, emailsInFile);
            rows.add(UserImportRowResponse.builder()
                    .rowNumber(index + 2)
                    .row(request)
                    .valid(errors.isEmpty())
                    .errors(errors)
                    .build());
        }

        int validRows = (int) rows.stream().filter(UserImportRowResponse::isValid).count();
        return UserImportResponse.builder()
                .totalRows(rows.size())
                .validRows(validRows)
                .invalidRows(rows.size() - validRows)
                .importedRows(0)
                .rows(rows)
                .build();
    }

    public UserImportResponse importUsers(List<AdminUserRequest> requests) {
        UserImportResponse result = previewImport(requests);
        int imported = 0;
        for (UserImportRowResponse row : result.getRows()) {
            if (!row.isValid()) {
                continue;
            }
            try {
                row.setCreatedUser(create(row.getRow()));
                imported++;
            } catch (RuntimeException exception) {
                row.setValid(false);
                row.setErrors(List.of(exception.getMessage() == null
                        ? "Không thể tạo tài khoản" : exception.getMessage()));
            }
        }
        int validRows = (int) result.getRows().stream().filter(UserImportRowResponse::isValid).count();
        result.setValidRows(validRows);
        result.setInvalidRows(result.getTotalRows() - validRows);
        result.setImportedRows(imported);
        return result;
    }

    @Transactional
    public UserResponse update(Long id, AdminUserRequest request, String actingUsername) {
        User user = findUser(id);
        validateDuplicates(request.getUsername(), request.getEmail(), id);
        Set<Role> roles = resolveRoles(request.getRoles());
        if (user.getUsername().equals(actingUsername)
                && user.getRoles().stream().anyMatch(r -> r.getName() == ERole.ROLE_ADMIN)
                && roles.stream().noneMatch(r -> r.getName() == ERole.ROLE_ADMIN)) {
            throw new IllegalArgumentException("Bạn không thể tự thu hồi vai trò quản trị của chính mình");
        }

        Set<Warehouse> warehouses = resolveWarehouses(request.getWarehouseIds());
        validateWarehouseAssignment(roles, warehouses);
        user.setUsername(request.getUsername().trim());
        user.setEmail(request.getEmail().trim().toLowerCase(Locale.ROOT));
        user.setFullName(request.getFullName().trim());
        user.setPhone(trimToNull(request.getPhone()));
        user.setRoles(roles);
        user.setWarehouses(warehouses);
        user.setTerritories(resolveTerritories(request.getTerritoryIds()));
        if (request.getDealerId() != null) {
            Dealer dealer = dealerRepository.findById(request.getDealerId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + request.getDealerId()));
            user.setDealer(dealer);
        } else {
            user.setDealer(null);
        }
        user.setTokenVersion(user.getTokenVersion() + 1);
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponse lock(Long id, String reason, String actingUsername) {
        String normalizedReason = validateLockReason(reason);
        User user = findUser(id);
        if (user.getUsername().equals(actingUsername)) {
            throw new IllegalArgumentException("Bạn không thể tự khoá tài khoản đang đăng nhập");
        }
        user.setActive(false);
        user.setLockReason(normalizedReason);
        user.setTokenVersion(user.getTokenVersion() + 1);

        List<Dealer> dealers = dealerRepository.findByAssignedUserId(id);
        dealers.forEach(dealer -> dealer.setHandoverRequired(true));
        dealerRepository.saveAll(dealers);
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponse unlock(Long id) {
        User user = findUser(id);
        user.setActive(true);
        user.setLockReason(null);
        user.setFailedLoginAttempts(0);
        user.setLockTime(null);
        return toResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public List<NamedResourceResponse> warehouses() {
        return warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(w -> new NamedResourceResponse(w.getId(), w.getCode(), w.getName())).toList();
    }

    @Transactional(readOnly = true)
    public List<NamedResourceResponse> territories() {
        return territoryRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(t -> new NamedResourceResponse(t.getId(), t.getCode(), t.getName())).toList();
    }

    public UserResponse toResponse(User user) {
        UserResponse.UserResponseBuilder builder = UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .avatarUrl(AvatarService.avatarUrl(user, false))
                .avatarThumbnailUrl(AvatarService.avatarUrl(user, true))
                .active(user.getActive())
                .lockReason(user.getLockReason())
                .mustChangePassword(user.getMustChangePassword())
                .roles(user.getRoles().stream().map(r -> r.getName().name()).sorted().toList())
                .warehouses(user.getWarehouses().stream()
                        .map(w -> new NamedResourceResponse(w.getId(), w.getCode(), w.getName())).toList())
                .territories(user.getTerritories().stream()
                        .map(t -> new NamedResourceResponse(t.getId(), t.getCode(), t.getName())).toList())
                .assignedDealerCount(dealerRepository.countByAssignedUserId(user.getId()))
                .createdAt(user.getCreatedAt());

        if (user.getDealer() != null) {
            builder.dealerId(user.getDealer().getId())
                    .dealerCode(user.getDealer().getCode())
                    .dealerName(user.getDealer().getName());
        }

        return builder.build();
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng"));
    }

    private Set<Role> resolveRoles(Set<String> roleNames) {
        Set<Role> roles = new HashSet<>();
        for (String value : roleNames) {
            try {
                ERole role = ERole.valueOf(value.toUpperCase(Locale.ROOT));
                roles.add(roleRepository.findByName(role)
                        .orElseThrow(() -> new IllegalArgumentException("Vai trò chưa được cấu hình: " + value)));
            } catch (IllegalArgumentException ex) {
                if (ex.getMessage() != null && ex.getMessage().startsWith("Vai trò")) throw ex;
                throw new IllegalArgumentException("Vai trò không hợp lệ: " + value);
            }
        }
        return roles;
    }

    private Set<Warehouse> resolveWarehouses(Set<Long> ids) {
        Set<Long> safeIds = ids == null ? Set.of() : ids;
        Set<Warehouse> result = new HashSet<>(warehouseRepository.findAllById(safeIds));
        if (result.size() != safeIds.size()) throw new IllegalArgumentException("Có kho không tồn tại");
        return result;
    }

    private Set<Territory> resolveTerritories(Set<Long> ids) {
        Set<Long> safeIds = ids == null ? Set.of() : ids;
        Set<Territory> result = new HashSet<>(territoryRepository.findAllById(safeIds));
        if (result.size() != safeIds.size()) throw new IllegalArgumentException("Có địa bàn không tồn tại");
        return result;
    }

    private void validateWarehouseAssignment(Set<Role> roles, Set<Warehouse> warehouses) {
        boolean needsWarehouse = roles.stream().map(Role::getName).anyMatch(WAREHOUSE_ROLES::contains);
        if (needsWarehouse && warehouses.isEmpty()) {
            throw new IllegalArgumentException("Người dùng thuộc vai trò kho phải được gắn ít nhất một kho");
        }
    }

    private void validateDuplicates(String username, String email, Long currentId) {
        userRepository.findByUsername(username.trim()).ifPresent(existing -> {
            if (!existing.getId().equals(currentId)) throw new IllegalArgumentException("Tên đăng nhập đã được sử dụng");
        });
        userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT)).ifPresent(existing -> {
            if (!existing.getId().equals(currentId)) throw new IllegalArgumentException("Email đã được sử dụng");
        });
    }

    private List<String> validateImportRow(AdminUserRequest request,
                                           Set<String> usernamesInFile,
                                           Set<String> emailsInFile) {
        List<String> errors = new ArrayList<>();
        if (request == null) {
            return List.of("Dòng dữ liệu trống");
        }

        String username = trimToNull(request.getUsername());
        String email = trimToNull(request.getEmail());
        String fullName = trimToNull(request.getFullName());
        String phone = trimToNull(request.getPhone());

        if (username == null || username.length() < 3 || username.length() > 50) {
            errors.add("Tên đăng nhập phải từ 3-50 ký tự");
        } else {
            String normalized = username.toLowerCase(Locale.ROOT);
            if (!usernamesInFile.add(normalized)) errors.add("Tên đăng nhập bị trùng trong tệp");
            if (userRepository.findByUsername(username).isPresent()) errors.add("Tên đăng nhập đã được sử dụng");
        }

        if (email == null || email.length() > 100 || !EMAIL_PATTERN.matcher(email).matches()) {
            errors.add("Email không hợp lệ");
        } else {
            String normalized = email.toLowerCase(Locale.ROOT);
            if (!emailsInFile.add(normalized)) errors.add("Email bị trùng trong tệp");
            if (userRepository.findByEmail(normalized).isPresent()) errors.add("Email đã được sử dụng");
        }

        if (fullName == null || fullName.length() > 100) errors.add("Họ tên không được để trống và tối đa 100 ký tự");
        if (phone != null && !VIETNAMESE_PHONE_PATTERN.matcher(phone).matches()) {
            errors.add("Số điện thoại không đúng định dạng Việt Nam");
        }
        if (request.getRoles() == null || request.getRoles().isEmpty()) {
            errors.add("Phải chọn ít nhất một vai trò");
        } else {
            try {
                Set<Role> roles = resolveRoles(request.getRoles());
                Set<Warehouse> warehouses = resolveWarehouses(request.getWarehouseIds());
                validateWarehouseAssignment(roles, warehouses);
                resolveTerritories(request.getTerritoryIds());
            } catch (IllegalArgumentException exception) {
                errors.add(exception.getMessage());
            }
        }
        return errors;
    }

    private String generateTemporaryPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder("A7");
        for (int i = 0; i < 10; i++) value.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        return value.toString();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String validateLockReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Bắt buộc nhập lý do khoá");
        }
        String normalized = reason.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("Lý do khoá tối đa 500 ký tự");
        }
        return normalized;
    }
}
