package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.*;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReturnOrderServiceImpl implements ReturnOrderService {
    private final ReturnOrderRepository returnOrderRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceAdjustmentRepository adjustmentRepository;
    private final AuditLogService auditLogService;
    private final DealerDebtService dealerDebtService;

    @Override @Transactional
    public ReturnOrderResponse create(CreateReturnOrderRequest request, String username) {
        Invoice invoice = lockInvoice(request.getInvoiceId());
        checkInvoice(invoice);
        var source = invoice.getItems().stream().collect(Collectors.toMap(InvoiceItem::getId, i -> i));
        Set<Long> seen = new HashSet<>();
        ReturnOrder result = ReturnOrder.builder()
                .returnCode("TR-" + UUID.randomUUID()).invoice(invoice).dealer(invoice.getDealer())
                .returnReason(request.getReturnReason().trim()).returnDate(request.getReturnDate())
                .note(request.getNote()).createdBy(username).createdAt(LocalDateTime.now()).build();
        BigDecimal total = BigDecimal.ZERO;
        for (ReturnOrderItemRequest row : request.getItems()) {
            if (!seen.add(row.getInvoiceItemId())) throw bad("Dòng hóa đơn bị lặp trong phiếu trả hàng");
            InvoiceItem original = source.get(row.getInvoiceItemId());
            if (original == null) throw bad("Dòng hàng không thuộc hóa đơn gốc");
            if (original.getOrder() == null) throw bad("Dòng hóa đơn chưa liên kết đơn hàng gốc");
            ReturnOrderItem item = ReturnOrderItem.builder().invoiceItem(original).product(original.getProduct())
                    .productCode(original.getProductCode()).productName(original.getProductName()).unit(original.getUnit())
                    .returnQuantity(row.getReturnQuantity()).invoiceUnitPrice(original.getUnitPrice())
                    .invoiceDiscountPerUnit(original.getDiscountAmount().divide(original.getInvoicedQuantity(), 6, RoundingMode.HALF_UP))
                    .note(row.getNote()).build();
            priceAndValidate(item);
            result.addItem(item);
            total = total.add(item.getReturnAmount());
        }
        result.setTotalReturnAmount(total);
        returnOrderRepository.saveAndFlush(result);
        auditLogService.logAction("RETURN_ORDER", result.getReturnCode(), "CREATE", null, "DRAFT", username);
        return mapToResponse(result);
    }

    /** Serialize all returns of the same invoice before checking the remaining quantities. */
    @Override @Transactional
    public ReturnOrderResponse confirm(Long id, String username) {
        Long invoiceId = returnOrderRepository.findInvoiceId(id).orElseThrow(() -> missing("phiếu trả hàng"));
        Invoice invoice = lockInvoice(invoiceId);
        checkInvoice(invoice);
        ReturnOrder result = lockedReturn(id);
        requireDraft(result);
        BigDecimal total = BigDecimal.ZERO;
        for (ReturnOrderItem item : result.getItems()) {
            priceAndValidate(item);
            total = total.add(item.getReturnAmount());
        }
        // A separate document records the credit. No field on the original invoice is changed.
        InvoiceAdjustment adjustment = InvoiceAdjustment.builder()
                .adjustmentCode("DCTH-" + UUID.randomUUID()).invoice(invoice)
                .adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentReason(result.getReturnReason()).adjustmentAmount(total.negate())
                .adjustedBy(username).adjustmentDate(LocalDateTime.now()).createdAt(LocalDateTime.now())
                .note("Phiếu trả hàng " + result.getReturnCode()).build();
        for (ReturnOrderItem item : result.getItems()) {
            adjustment.addItem(InvoiceAdjustmentItem.builder().product(item.getProduct())
                    .productCode(item.getProductCode()).productName(item.getProductName()).unit(item.getUnit())
                    .adjustedQuantity(item.getReturnQuantity().negate()).adjustedPrice(item.getInvoiceUnitPrice())
                    .adjustmentAmount(item.getReturnAmount().negate()).reason(item.getNote()).build());
        }
        adjustmentRepository.saveAndFlush(adjustment);
        result.setTotalReturnAmount(total);
        result.setInvoiceAdjustment(adjustment);
        result.setStatus(ReturnOrderStatus.CONFIRMED);
        result.setConfirmedBy(username);
        result.setConfirmedAt(LocalDateTime.now());
        returnOrderRepository.saveAndFlush(result);
        // Evaluation is part of the transaction. A failure must roll back the credit as well.
        dealerDebtService.evaluateDealerDebt(result.getDealer().getId());
        auditLogService.logAction("RETURN_ORDER", result.getReturnCode(), "CONFIRM", "DRAFT", "CONFIRMED", username);
        auditLogService.logAction("INVOICE_ADJUSTMENT", adjustment.getAdjustmentCode(), "CREATE", null,
                total.negate().toPlainString(), username);
        return mapToResponse(result);
    }

    private void priceAndValidate(ReturnOrderItem item) {
        InvoiceItem original = item.getInvoiceItem();
        BigDecimal quantity = item.getReturnQuantity();
        if (quantity == null || quantity.signum() <= 0 || quantity.scale() > 4) throw bad("Số lượng trả phải dương, tối đa 4 chữ số thập phân");
        BigDecimal previousQuantity = returnOrderRepository.confirmedQuantity(original.getId());
        BigDecimal previousAmount = returnOrderRepository.confirmedAmount(original.getId());
        BigDecimal cumulative = previousQuantity.add(quantity);
        if (cumulative.compareTo(original.getInvoicedQuantity()) > 0) throw bad("Tổng số lượng trả vượt số lượng trên hóa đơn gốc");
        // Difference between cumulative rounded totals avoids losing cents on split returns.
        BigDecimal cumulativeAmount = original.getTotalAmount().multiply(cumulative)
                .divide(original.getInvoicedQuantity(), 2, RoundingMode.HALF_UP);
        item.setReturnAmount(cumulativeAmount.subtract(previousAmount));
    }
    @Override @Transactional
    public ReturnOrderResponse cancel(Long id, CancelReturnOrderRequest request, String username) {
        ReturnOrder result = lockedReturn(id);
        requireDraft(result);
        result.setStatus(ReturnOrderStatus.CANCELLED);
        result.setCancelReason(request.getCancelReason().trim());
        auditLogService.logAction("RETURN_ORDER", result.getReturnCode(), "CANCEL", "DRAFT", "CANCELLED", username);
        return mapToResponse(result);
    }
    @Override
    public ReturnOrderResponse getById(Long id) {
        return mapToResponse(returnOrderRepository.findById(id).orElseThrow(() -> missing("phiếu trả hàng")));
    }
    @Override
    public List<ReturnOrderResponse> getByInvoiceId(Long invoiceId) {
        if (!invoiceRepository.existsById(invoiceId)) throw missing("hóa đơn");
        return returnOrderRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream().map(this::mapToResponse).toList();
    }
    @Override
    public Page<ReturnOrderResponse> search(Long dealerId, Long invoiceId, String status, Pageable pageable) {
        ReturnOrderStatus selected;
        try { selected = status == null ? null : ReturnOrderStatus.valueOf(status); }
        catch (IllegalArgumentException ex) { throw bad("Trạng thái phiếu trả hàng không hợp lệ"); }
        Specification<ReturnOrder> spec = (r, q, b) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (dealerId != null) predicates.add(b.equal(r.get("dealer").get("id"), dealerId));
            if (invoiceId != null) predicates.add(b.equal(r.get("invoice").get("id"), invoiceId));
            if (selected != null) predicates.add(b.equal(r.get("status"), selected));
            return b.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return returnOrderRepository.findAll(spec, pageable).map(this::mapToResponse);
    }
    private Invoice lockInvoice(Long id) {
        return invoiceRepository.findForReturnUpdate(id).orElseThrow(() -> missing("hóa đơn"));
    }
    private ReturnOrder lockedReturn(Long id) {
        return returnOrderRepository.findForUpdate(id).orElseThrow(() -> missing("phiếu trả hàng"));
    }
    private void requireDraft(ReturnOrder r) {
        if (r.getStatus() != ReturnOrderStatus.DRAFT) throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiếu trả hàng không còn ở trạng thái nháp");
    }
    private void checkInvoice(Invoice i) {
        if (i.getStatus() == InvoiceStatus.CANCELLED) throw bad("Không được trả hàng trên hóa đơn đã hủy");
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException missing(String type) { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy " + type); }

    private ReturnOrderResponse mapToResponse(ReturnOrder r) {
        List<ReturnOrderItemResponse> items = r.getItems().stream()
                .map(it -> ReturnOrderItemResponse.builder()
                        .id(it.getId())
                        .invoiceItemId(it.getInvoiceItem().getId())
                        .productId(it.getProduct() != null ? it.getProduct().getId() : null)
                        .productCode(it.getProductCode())
                        .productName(it.getProductName())
                        .unit(it.getUnit())
                        .returnQuantity(it.getReturnQuantity())
                        .invoiceUnitPrice(it.getInvoiceUnitPrice())
                        .invoiceDiscountPerUnit(it.getInvoiceDiscountPerUnit())
                        .returnAmount(it.getReturnAmount())
                        .note(it.getNote())
                        .build())
                .collect(Collectors.toList());

        InvoiceAdjustmentResponse adjResp = null;
        if (r.getInvoiceAdjustment() != null) {
            InvoiceAdjustment adj = r.getInvoiceAdjustment();
            List<InvoiceAdjustmentItemResponse> adjItems = adj.getItems().stream()
                    .map(ai -> InvoiceAdjustmentItemResponse.builder()
                            .id(ai.getId())
                            .productId(ai.getProduct() != null ? ai.getProduct().getId() : null)
                            .productCode(ai.getProductCode())
                            .productName(ai.getProductName())
                            .unit(ai.getUnit())
                            .adjustedQuantity(ai.getAdjustedQuantity())
                            .adjustedPrice(ai.getAdjustedPrice())
                            .adjustmentAmount(ai.getAdjustmentAmount())
                            .reason(ai.getReason())
                            .build())
                    .collect(Collectors.toList());

            adjResp = InvoiceAdjustmentResponse.builder()
                    .id(adj.getId())
                    .adjustmentCode(adj.getAdjustmentCode())
                    .invoiceId(adj.getInvoice() != null ? adj.getInvoice().getId() : null)
                    .invoiceCode(adj.getInvoice() != null ? adj.getInvoice().getInvoiceCode() : null)
                    .invoiceNumber(adj.getInvoice() != null ? adj.getInvoice().getInvoiceNumber() : null)
                    .adjustmentType(adj.getAdjustmentType())
                    .adjustmentTypeLabel(adj.getAdjustmentType() != null ? adj.getAdjustmentType().getDisplayName() : null)
                    .adjustmentReason(adj.getAdjustmentReason())
                    .adjustmentDate(adj.getAdjustmentDate())
                    .adjustmentAmount(adj.getAdjustmentAmount())
                    .adjustedBy(adj.getAdjustedBy())
                    .createdAt(adj.getCreatedAt())
                    .note(adj.getNote())
                    .items(adjItems)
                    .build();
        }

        Invoice invoice = r.getInvoice();
        Dealer dealer = r.getDealer();

        return ReturnOrderResponse.builder()
                .id(r.getId())
                .returnCode(r.getReturnCode())
                .invoiceId(invoice != null ? invoice.getId() : null)
                .invoiceCode(invoice != null ? invoice.getInvoiceCode() : null)
                .invoiceNumber(invoice != null ? invoice.getInvoiceNumber() : null)
                .dealerId(dealer != null ? dealer.getId() : null)
                .dealerCode(dealer != null ? dealer.getCode() : null)
                .dealerName(dealer != null ? dealer.getName() : null)
                .status(r.getStatus())
                .statusLabel(r.getStatus() != null ? r.getStatus().getDisplayName() : null)
                .returnReason(r.getReturnReason())
                .returnDate(r.getReturnDate())
                .totalReturnAmount(r.getTotalReturnAmount())
                .invoiceAdjustment(adjResp)
                .confirmedBy(r.getConfirmedBy())
                .confirmedAt(r.getConfirmedAt())
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAt())
                .cancelReason(r.getCancelReason())
                .note(r.getNote())
                .items(items)
                .build();
    }
}