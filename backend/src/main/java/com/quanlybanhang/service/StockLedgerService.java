package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockLedgerService {
    private final StockLedgerRepository ledger;
    private final WarehouseRepository warehouses;
    private final CategoryRepository categories;
    private final UserRepository users;

    public StockLedgerOptionsResponse options() {
        return new StockLedgerOptionsResponse(visibleWarehouses().stream()
                .map(w -> new NamedResourceResponse(w.getId(), w.getCode(), w.getName())).toList(),
                categories.findAllByOrderByLevelAscNameAsc().stream()
                        .map(c -> new NamedResourceResponse(c.getId(), c.getCode(), c.getName())).toList(),
                Arrays.stream(StockStatus.values())
                        .map(s -> new StockLedgerOptionsResponse.StatusOption(s.name(), s.getLabel())).toList());
    }

    public Page<StockLedgerEntryResponse> search(Long warehouseId, Long categoryId, String keyword,
            StockStatus status, int page, int size) {
        return search(warehouseId, categoryId, keyword, status, false, page, size);
    }

    public Page<StockLedgerEntryResponse> searchBelowMinimum(Long warehouseId, Long categoryId, String keyword,
            StockStatus status, int page, int size) {
        return search(warehouseId, categoryId, keyword, status, true, page, size);
    }

    private Page<StockLedgerEntryResponse> search(Long warehouseId, Long categoryId, String keyword,
            StockStatus status, boolean belowMinimum, int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Trang phải không âm; số dòng mỗi trang từ 1 đến 200");
        var query = prepareQuery(warehouseId, categoryId, keyword, status, belowMinimum);
        var paging = PageRequest.of(page, size);
        if (query.warehouseIds().isEmpty()) return Page.empty(paging);
        var rows = belowMinimum ? ledger.findBelowMinimum(query.warehouseIds(), warehouseId, categoryId, query.keyword(), query.stockStatus(), paging)
                : ledger.findLedger(query.warehouseIds(), warehouseId, categoryId, query.keyword(), query.stockStatus(), paging);
        return rows.map(StockLedgerEntryResponse::from);
    }

    public record Query(List<Long> warehouseIds, List<String> warehouseCodes, Long warehouseId, Long categoryId,
            String keyword, String stockStatus, boolean belowMinimum) {}

    /** The UI and Excel export share validation, literal keyword escaping and the same DB-backed scope. */
    public Query prepareQuery(Long warehouseId, Long categoryId, String keyword, StockStatus status, boolean belowMinimum) {
        if ((warehouseId != null && warehouseId < 1) || (categoryId != null && categoryId < 1))
            throw new IllegalArgumentException("Mã kho/nhóm hàng không hợp lệ");
        if (keyword != null && keyword.length() > 150) throw new IllegalArgumentException("Từ khóa tối đa 150 ký tự");
        List<Warehouse> visible = visibleWarehouses();
        if (warehouseId != null && visible.stream().noneMatch(w -> w.getId().equals(warehouseId)))
            throw new AccessDeniedException("Bạn không có quyền xem tồn của kho này");
        List<Warehouse> selected = visible.stream().filter(w -> warehouseId == null || w.getId().equals(warehouseId)).toList();
        String term = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return new Query(selected.stream().map(Warehouse::getId).toList(), selected.stream().map(Warehouse::getCode).toList(),
                warehouseId, categoryId, term, status == null ? null : status.name(), belowMinimum);
    }
    /** Permission is mandatory; roles may ONLY restrict data scope, never grant access. */
    private List<Warehouse> visibleWarehouses() {
        return visibleWarehousesFor("WAREHOUSE_VIEW");
    }

    /** Internal scope helper; callers cannot replace a missing matrix permission with a role. */
    public List<Warehouse> visibleWarehousesFor(String permission) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Bạn chưa được xác thực");
        Set<String> grants = new HashSet<>();
        auth.getAuthorities().forEach(a -> grants.add(a.getAuthority()));
        boolean admin = grants.contains("ROLE_ADMIN");
        if (!admin && !grants.contains(permission)) throw new AccessDeniedException("Bạn không có quyền sử dụng chức năng kho này");
        List<Warehouse> all = warehouses.findAllByOrderByCodeAsc();
        if (admin) return all;
        User user = users.findByUsername(auth.getName()).orElseThrow(() -> new AccessDeniedException("Không tìm thấy tài khoản"));
        Set<Long> assigned = new HashSet<>();
        if (user.getWarehouses() != null) user.getWarehouses().forEach(w -> assigned.add(w.getId()));
        boolean warehouseRole = grants.contains("ROLE_WAREHOUSE_STAFF") || grants.contains("ROLE_WAREHOUSE_MANAGER");
        // Explicit assignments constrain any role; warehouse roles with missing assignments fail closed.
        if (!assigned.isEmpty() || warehouseRole) return all.stream().filter(w -> assigned.contains(w.getId())).toList();
        return all;
    }
}
