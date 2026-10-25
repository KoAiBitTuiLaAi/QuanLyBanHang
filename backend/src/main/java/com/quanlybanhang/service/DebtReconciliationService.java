package com.quanlybanhang.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.dto.DebtReconciliationDtos.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.model.DebtReconciliation.Status;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class DebtReconciliationService {
    private final DebtReconciliationRepository records;
    private final DealerRepository dealers;
    private final UserRepository users;
    private final DealerDebtStatementService statements;
    private final ObjectMapper json;
    private final AuditLogService audit;

    private boolean has(Authentication a, String grant) {
        return a != null && a.isAuthenticated() && a.getAuthorities().stream().anyMatch(g -> grant.equals(g.getAuthority()));
    }
    private void requireView(Authentication a) {
        if (!has(a, "ROLE_ADMIN") && !has(a, "FINANCE_VIEW_DEBT"))
            throw new AccessDeniedException("Không có quyền xem đối chiếu công nợ");
    }
    private User user(Authentication a) {
        return users.findByUsername(a.getName()).orElseThrow(() -> new AccessDeniedException("Không tìm thấy tài khoản"));
    }
    private Long ownDealer(Authentication a) {
        if (has(a, "ROLE_ADMIN")) return null;
        if (!has(a, "ROLE_DEALER")) throw new AccessDeniedException("Chức năng dành cho đại lý");
        var dealer = user(a).getDealer();
        if (dealer == null) throw new AccessDeniedException("Tài khoản chưa liên kết đại lý");
        return dealer.getId();
    }
    private Long assignedSales(Authentication a) {
        boolean broad = has(a, "ROLE_ADMIN") || has(a, "ROLE_ACCOUNTANT")
                || has(a, "ROLE_SALES_MANAGER") || has(a, "ROLE_VIEWER");
        return has(a, "ROLE_SALES") && !broad ? user(a).getId() : null;
    }
    private void scope(Dealer dealer, Authentication a, boolean portal) {
        requireView(a);
        if (portal || has(a, "ROLE_DEALER") && !has(a, "ROLE_ADMIN")) {
            Long own = ownDealer(a);
            if (own != null && !own.equals(dealer.getId())) throw new AccessDeniedException("Chỉ đối chiếu đại lý của mình");
        } else {
            Long assigned = assignedSales(a);
            if (assigned != null && (dealer.getAssignedUser() == null || !assigned.equals(dealer.getAssignedUser().getId())))
                throw new AccessDeniedException("Đại lý không thuộc phạm vi phụ trách");
        }
    }
    private void period(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || from.getYear() < 1000 || to.getYear() > 9998)
            throw new IllegalArgumentException("Khoảng ngày đối chiếu không hợp lệ");
    }
    private DebtReconciliation record(Long id, Authentication a, boolean portal, boolean lock) {
        requireView(a);
        if (id == null || id < 1) throw new IllegalArgumentException("Mã biên bản không hợp lệ");
        var r = (lock ? records.findForUpdate(id) : records.findById(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy biên bản"));
        scope(r.getDealer(), a, portal);
        return r;
    }
    public Page<DealerOption> options(String keyword, int page, Authentication a, boolean portal) {
        requireView(a);
        if (page < 0) throw new IllegalArgumentException("Trang không hợp lệ");
        if (portal || has(a, "ROLE_DEALER") && !has(a, "ROLE_ADMIN")) {
            Long own = ownDealer(a);
            if (own != null) {
                var d = dealers.findById(own).orElseThrow();
                return new PageImpl<>(page == 0 ? List.of(option(d)) : List.of(), PageRequest.of(page, 50), 1);
            }
        }
        return dealers.searchDealers(keyword, null, null, assignedSales(a), null, null,
                PageRequest.of(page, 50, Sort.by("name").ascending().and(Sort.by("id"))))
                .map(this::option);
    }
    private DealerOption option(Dealer d) { return new DealerOption(d.getId(), d.getCode(), d.getName()); }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Detail create(Create request, Authentication a) {
        requireView(a);
        if (!has(a, "ROLE_ADMIN") && (!has(a, "FINANCE_MANAGE") || has(a, "ROLE_DEALER")))
            throw new AccessDeniedException("Cần quyền FINANCE_MANAGE để lập biên bản; đại lý chỉ được phản hồi");
        period(request.from(), request.to());
        var dealer = dealers.findById(request.dealerId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đại lý"));
        scope(dealer, a, false);
        String key = request.requestKey().toString();
        var old = records.findByRequestKey(key);
        if (old.isPresent()) {
            var r = old.get();
            if (!r.getCreatedBy().equals(a.getName()) || !r.getDealer().getId().equals(request.dealerId())
                    || !r.getFrom().equals(request.from()) || !r.getTo().equals(request.to()))
                throw conflict("Mã yêu cầu đã dùng cho biên bản khác");
            return detail(r);
        }
        // All snapshot reads join this repeatable-read transaction; never recalculate a saved document.
        var live = statements.statement(dealer.getId(), request.from(), request.to());
        var entries = live.entries().stream().map(e -> new Entry(e.occurredAt(), e.sourceType(), e.sourceId(),
                e.documentCode(), e.description(), money(e.increase()), money(e.decrease()), money(e.balance()))).toList();
        var snapshot = new Snapshot(dealer.getId(), dealer.getCode(), dealer.getName(), dealer.getAddress(), dealer.getPhone(),
                live.from(), live.to(), money(live.openingBalance()), money(live.totalIncrease()),
                money(live.totalDecrease()), money(live.closingBalance()), entries);
        String encoded;
        try { encoded = json.writeValueAsString(snapshot); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Không lưu được số liệu biên bản", ex); }
        var r = DebtReconciliation.builder().code("DC-" + UUID.randomUUID()).requestKey(key).dealer(dealer)
                .from(request.from()).to(request.to()).snapshotJson(encoded).status(Status.PENDING)
                .createdBy(a.getName()).createdAt(LocalDateTime.now()).build();
        records.saveAndFlush(r);
        audit.logAction("DEBT_RECONCILIATION", r.getCode(), "CREATE", null, "PENDING", a.getName());
        return detail(r);
    }
    public Page<Summary> list(Long dealerId, LocalDate from, LocalDate to, int page, Authentication a, boolean portal) {
        requireView(a);
        if (page < 0 || dealerId != null && dealerId < 1) throw new IllegalArgumentException("Tham số không hợp lệ");
        if (from != null || to != null) period(from, to);
        Long own = portal || has(a, "ROLE_DEALER") && !has(a, "ROLE_ADMIN") ? ownDealer(a) : null;
        if (own != null && dealerId != null && !own.equals(dealerId)) throw new AccessDeniedException("Chỉ xem đại lý của mình");
        Long selected = own != null ? own : dealerId;
        Long assigned = own == null && !portal ? assignedSales(a) : null;
        Specification<DebtReconciliation> spec = (r, q, b) -> {
            var filters = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (selected != null) filters.add(b.equal(r.get("dealer").get("id"), selected));
            if (assigned != null) filters.add(b.equal(r.get("dealer").get("assignedUser").get("id"), assigned));
            if (from != null) filters.add(b.greaterThanOrEqualTo(r.get("from"), from));
            if (to != null) filters.add(b.lessThanOrEqualTo(r.get("to"), to));
            return b.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return records.findAll(spec, PageRequest.of(page, 20, Sort.by("id").descending())).map(r -> {
            var s = snapshot(r);
            return new Summary(r.getId(), r.getVersion(), r.getCode(), s.dealerId(), s.dealerName(), r.getFrom(), r.getTo(),
                    s.closingBalance(), r.getStatus(), r.getCreatedBy(), r.getCreatedAt());
        });
    }
    public Detail get(Long id, Authentication a, boolean portal) { return detail(record(id, a, portal, false)); }
    @Transactional
    public Detail respond(Long id, Feedback request, Authentication a) {
        var r = record(id, a, true, true);
        // Even ADMIN cannot impersonate a dealer's agreement.
        if (has(a, "ROLE_ADMIN") || !has(a, "ROLE_DEALER"))
            throw new AccessDeniedException("Chỉ tài khoản đại lý liên kết được xác nhận hoặc phản hồi");
        if (!Objects.equals(r.getVersion(), request.version()) || r.getStatus() != Status.PENDING)
            throw conflict("Biên bản đã được phản hồi hoặc thay đổi; vui lòng tải lại");
        if (request.status() != Status.CONFIRMED && request.status() != Status.DISPUTED)
            throw new IllegalArgumentException("Phản hồi phải là CONFIRMED hoặc DISPUTED");
        var closing = new BigDecimal(snapshot(r).closingBalance());
        if (request.status() == Status.DISPUTED) {
            if (request.claimedBalance() == null || request.reason() == null || request.reason().isBlank()
                    || request.reason().trim().length() > 500 || request.claimedBalance().compareTo(closing) == 0)
                throw new IllegalArgumentException("Phản hồi lệch cần số dư khác hệ thống và lý do không quá 500 ký tự");
        } else if (request.claimedBalance() != null && request.claimedBalance().compareTo(closing) != 0) {
            throw new IllegalArgumentException("Số dư xác nhận phải khớp số liệu biên bản");
        }
        r.setStatus(request.status());
        r.setClaimedBalance(request.status() == Status.CONFIRMED ? closing : request.claimedBalance());
        r.setResponseReason(request.status() == Status.DISPUTED ? request.reason().trim() : null);
        r.setRespondedBy(a.getName()); r.setRespondedAt(LocalDateTime.now());
        records.saveAndFlush(r);
        audit.logAction("DEBT_RECONCILIATION", r.getCode(), request.status().name(), "PENDING",
                money(r.getClaimedBalance()), r.getResponseReason(), a.getName());
        return detail(r);
    }
    private Snapshot snapshot(DebtReconciliation r) {
        try { return json.readValue(r.getSnapshotJson(), Snapshot.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Không đọc được số liệu biên bản", ex); }
    }
    private Detail detail(DebtReconciliation r) {
        var s = snapshot(r);
        String difference = r.getClaimedBalance() == null ? null : money(new BigDecimal(s.closingBalance()).subtract(r.getClaimedBalance()));
        return new Detail(r.getId(), r.getVersion(), r.getCode(), r.getStatus(), r.getCreatedBy(), r.getCreatedAt(),
                r.getRespondedBy(), r.getRespondedAt(), money(r.getClaimedBalance()), difference, r.getResponseReason(), s);
    }
    private String money(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
