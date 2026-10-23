package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.CashCollectionRequest;
import com.quanlybanhang.dto.response.CashCollectionResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class CashCollectionService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final CashCollectionRepository collections;
    private final DealerRepository dealers;
    private final UserRepository users;
    private final AuditLogService audit;

    private boolean has(Authentication a, String permission) {
        return a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN") || g.getAuthority().equals(permission));
    }
    private User actor(Authentication a) {
        return users.findByUsername(a.getName()).orElseThrow(() -> new AccessDeniedException("Không tìm thấy tài khoản"));
    }
    private void scope(CashCollection c, Authentication a) {
        if (!has(a, "COLLECTION_RECONCILE") && !has(a, "COLLECTION_CONFIRM")
                && !c.getEmployee().getId().equals(actor(a).getId()))
            throw new AccessDeniedException("Không có quyền xem khoản thu của nhân viên khác");
    }
    private CashCollection find(Long id) {
        return collections.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy khoản thu"));
    }
    @Transactional
    public CashCollectionResponse create(CashCollectionRequest request, MultipartFile image, Authentication a) {
        User employee = actor(a);
        Dealer dealer = dealers.findById(request.dealerId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy cửa hàng"));
        if (!Boolean.TRUE.equals(dealer.getActive())) throw bad("Cửa hàng đã ngừng giao dịch");
        if (!has(a, "ROLE_ADMIN") && (dealer.getAssignedUser() == null || !employee.getId().equals(dealer.getAssignedUser().getId())))
            throw new AccessDeniedException("Cửa hàng không thuộc nhân viên phụ trách");
        if (request.collectionDate().isAfter(LocalDate.now(ZONE))) throw bad("Ngày thu không được ở tương lai");
        if (image == null || image.isEmpty() || image.getSize() > 5 * 1024 * 1024) throw bad("Biên lai bắt buộc, tối đa 5 MB");
        byte[] bytes;
        String contentType;
        try {
            bytes = image.getBytes();
            try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw bad("Biên lai phải là ảnh JPEG hoặc PNG hợp lệ");
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    String format = reader.getFormatName();
                    if (!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG")) throw bad("Chỉ nhận JPEG hoặc PNG");
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > 20_000_000L) throw bad("Ảnh vượt quá 20 megapixel");
                    if (reader.read(0) == null) throw bad("Ảnh không hợp lệ");
                    contentType = format.equalsIgnoreCase("PNG") ? "image/png" : "image/jpeg";
                } finally { reader.dispose(); }
            }
        } catch (IOException e) { throw bad("Không đọc được ảnh biên lai"); }
        CashCollection c = new CashCollection();
        c.setDealer(dealer); c.setEmployee(employee); c.setAmount(request.amount());
        c.setCollectionDate(request.collectionDate()); c.setNote(request.note());
        c.setRouteName(dealer.getTerritory() == null ? null : dealer.getTerritory().getName());
        c.setReceipt(bytes); c.setReceiptContentType(contentType); c.setCreatedAt(LocalDateTime.now(ZONE));
        collections.saveAndFlush(c);
        audit.logAction("CashCollection", c.getId().toString(), "CREATE", null, c.getAmount().toPlainString(), a.getName());
        return CashCollectionResponse.from(c);
    }
    @Transactional
    public CashCollectionResponse confirm(Long id, Authentication a) {
        CashCollection c = collections.findForConfirmation(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy khoản thu"));
        User receiver = actor(a);
        if (c.getEmployee().getId().equals(receiver.getId())) throw new AccessDeniedException("Không được tự xác nhận khoản thu của mình");
        if (c.getStatus() != CashCollection.Status.PENDING) throw new ResponseStatusException(HttpStatus.CONFLICT, "Khoản thu đã được xác nhận");
        c.setStatus(CashCollection.Status.RECEIVED); c.setReceivedBy(receiver); c.setReceivedAt(LocalDateTime.now(ZONE));
        audit.logAction("CashCollection", id.toString(), "CONFIRM_RECEIVED", "PENDING", "RECEIVED", a.getName());
        return CashCollectionResponse.from(c);
    }
    public CashCollectionResponse detail(Long id, Authentication a) {
        CashCollection c = find(id); scope(c, a); return CashCollectionResponse.from(c);
    }
    public record Receipt(byte[] bytes, String contentType) {}
    public Receipt receipt(Long id, Authentication a) {
        CashCollection c = find(id); scope(c, a); return new Receipt(c.getReceipt(), c.getReceiptContentType());
    }
    private Specification<CashCollection> filter(LocalDate from, LocalDate to, Long employeeId, Authentication a) {
        if (from == null || to == null || from.isAfter(to) || from.plusDays(366).isBefore(to)) throw bad("Khoảng ngày không hợp lệ, tối đa 366 ngày");
        Long effective = employeeId;
        if (!has(a, "COLLECTION_RECONCILE") && !has(a, "COLLECTION_CONFIRM")) {
            Long own = actor(a).getId();
            if (effective != null && !effective.equals(own)) throw new AccessDeniedException("Không có quyền xem nhân viên khác");
            effective = own;
        }
        Long selected = effective;
        return (r, q, b) -> selected == null ? b.between(r.get("collectionDate"), from, to)
                : b.and(b.between(r.get("collectionDate"), from, to), b.equal(r.get("employee").get("id"), selected));
    }
    public Page<CashCollectionResponse> list(LocalDate from, LocalDate to, Long employeeId, int page, int size, Authentication a) {
        return collections.findAll(filter(from, to, employeeId, a), PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)), Sort.by("id").descending())).map(CashCollectionResponse::from);
    }
    public record Reconciliation(Long employeeId, String employeeName, LocalDate date,
            BigDecimal collected, BigDecimal received, BigDecimal pending, long count) {}
    public List<Reconciliation> reconcile(LocalDate from, LocalDate to, Long employeeId, Authentication a) {
        // Validate the same scope and date bounds as the list endpoint before aggregation.
        filter(from, to, employeeId, a);
        Long selected = !has(a, "COLLECTION_RECONCILE") && !has(a, "COLLECTION_CONFIRM") ? actor(a).getId() : employeeId;
        return collections.dailyTotals(from, to, selected, CashCollection.Status.RECEIVED).stream()
                .map(row -> new Reconciliation(row.getEmployeeId(), row.getEmployeeName(), row.getDate(),
                        row.getCollected(), row.getReceived(), row.getCollected().subtract(row.getReceived()), row.getCount()))
                .toList();
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}