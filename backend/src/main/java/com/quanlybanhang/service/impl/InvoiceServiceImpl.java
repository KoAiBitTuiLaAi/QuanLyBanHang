package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.request.CreateInvoiceAdjustmentRequest;
import com.quanlybanhang.dto.request.CreateInvoiceRequest;
import com.quanlybanhang.dto.request.InvoiceAdjustmentItemRequest;
import com.quanlybanhang.dto.request.InvoicePreviewRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import com.quanlybanhang.service.InvoiceSequenceService;
import com.quanlybanhang.service.InvoiceService;
import com.quanlybanhang.service.FinancialReturnCredits;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceAdjustmentRepository adjustmentRepository;
    private final DealerRepository dealerRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final InvoiceSequenceService invoiceSequenceService;
    private final AuditLogService auditLogService;

    @Autowired(required = false)
    private DebtPaymentRepository debtPaymentRepository;

    @Autowired(required = false)
    private ReturnOrderRepository returnOrderRepository;

    public void setDebtPaymentRepository(DebtPaymentRepository debtPaymentRepository) {
        this.debtPaymentRepository = debtPaymentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public InvoicePreviewResponse previewInvoice(InvoicePreviewRequest request, String username) {
        if (request.getDealerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID đại lý không được để trống");
        }
        if (request.getOrderIds() == null || request.getOrderIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Danh sách phiếu giao không được để trống");
        }

        Dealer dealer = dealerRepository.findById(request.getDealerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đại lý với ID: " + request.getDealerId()));

        List<Order> orders = orderRepository.findAllById(request.getOrderIds());
        validateOrdersForInvoicing(dealer, orders, request.getOrderIds());

        List<InvoiceItemResponse> itemResponses = new ArrayList<>();
        List<InvoiceOrderSummaryResponse> orderSummaries = new ArrayList<>();

        BigDecimal totalSubtotal = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Order order : orders) {
            BigDecimal orderDeliveredTotal = BigDecimal.ZERO;
            int orderItemCount = 0;

            for (OrderItem orderItem : order.getItems()) {
                BigDecimal deliveredQty = orderItem.getDeliveredQuantity();
                if (deliveredQty == null || deliveredQty.compareTo(BigDecimal.ZERO) <= 0) {
                    // Bỏ qua dòng hàng không thực giao (tiêu chí S7-01: hóa đơn sinh từ số lượng thực giao)
                    continue;
                }

                BigDecimal unitPrice = orderItem.getPrice() != null ? orderItem.getPrice() : BigDecimal.ZERO;
                BigDecimal lineSubtotal = deliveredQty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);

                BigDecimal discountAmount = BigDecimal.ZERO;
                if (orderItem.getDiscountPercent() != null && orderItem.getDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
                    discountAmount = lineSubtotal.multiply(orderItem.getDiscountPercent())
                            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                } else if (orderItem.getDiscountAmount() != null && orderItem.getQuantity() != null && orderItem.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    // Phân bổ chiết khấu theo tỷ lệ thực giao / đặt
                    discountAmount = orderItem.getDiscountAmount().multiply(deliveredQty)
                            .divide(orderItem.getQuantity(), 2, RoundingMode.HALF_UP);
                }

                BigDecimal lineTotal = lineSubtotal.subtract(discountAmount);

                totalSubtotal = totalSubtotal.add(lineSubtotal);
                totalDiscount = totalDiscount.add(discountAmount);
                totalAmount = totalAmount.add(lineTotal);

                orderDeliveredTotal = orderDeliveredTotal.add(lineTotal);
                orderItemCount++;

                itemResponses.add(InvoiceItemResponse.builder()
                        .orderId(order.getId())
                        .orderCode(order.getOrderCode())
                        .orderItemId(orderItem.getId())
                        .productId(orderItem.getProduct().getId())
                        .productCode(orderItem.getProduct().getCode())
                        .productName(orderItem.getProduct().getName())
                        .unit(orderItem.getUnit())
                        .orderedQuantity(orderItem.getQuantity())
                        .deliveredQuantity(deliveredQty)
                        .invoicedQuantity(deliveredQty)
                        .unitPrice(unitPrice)
                        .subtotalAmount(lineSubtotal)
                        .discountPercent(orderItem.getDiscountPercent())
                        .discountAmount(discountAmount)
                        .totalAmount(lineTotal)
                        .note(orderItem.getDeliveryNote())
                        .build());
            }

            orderSummaries.add(InvoiceOrderSummaryResponse.builder()
                    .orderId(order.getId())
                    .orderCode(order.getOrderCode())
                    .actualDeliveryDate(order.getActualDeliveryDate())
                    .deliveredTotalAmount(orderDeliveredTotal)
                    .itemCount(orderItemCount)
                    .build());
        }

        if (itemResponses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Các phiếu giao đã chọn không có mặt hàng nào được giao thực tế (số lượng thực giao bằng 0)");
        }

        return InvoicePreviewResponse.builder()
                .dealerId(dealer.getId())
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .dealerTaxCode(dealer.getTaxCode())
                .dealerAddress(dealer.getAddress())
                .dealerPhone(dealer.getPhone())
                .totalOrders(orders.size())
                .totalItems(itemResponses.size())
                .subtotalAmount(totalSubtotal)
                .discountAmount(totalDiscount)
                .totalAmount(totalAmount)
                .items(itemResponses)
                .orders(orderSummaries)
                .build();
    }

    @Override
    @Transactional
    public InvoiceResponse issueInvoice(CreateInvoiceRequest request, String username) {
        if (request.getDealerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID đại lý không được để trống");
        }
        if (request.getOrderIds() == null || request.getOrderIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Danh sách phiếu giao hàng không được để trống");
        }

        Dealer dealer = dealerRepository.findById(request.getDealerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đại lý với ID: " + request.getDealerId()));

        List<Order> orders = orderRepository.findAllById(request.getOrderIds());
        validateOrdersForInvoicing(dealer, orders, request.getOrderIds());

        // Sinh số hóa đơn liên tục không nhảy cóc trong transaction
        InvoiceSequenceService.NextNumberResult nextNumber = invoiceSequenceService.nextSequentialNumber(
                InvoiceSequenceService.DEFAULT_SEQUENCE_KEY,
                InvoiceSequenceService.DEFAULT_PREFIX,
                InvoiceSequenceService.DEFAULT_PADDING_LENGTH
        );

        LocalDateTime invoiceDate = request.getInvoiceDate() != null ? request.getInvoiceDate() : LocalDateTime.now();
        LocalDate dueDate = request.getDueDate();
        if (dueDate == null) {
            int debtDays = (dealer.getMaxDebtDays() != null && dealer.getMaxDebtDays() > 0)
                    ? dealer.getMaxDebtDays()
                    : 30;
            dueDate = invoiceDate.toLocalDate().plusDays(debtDays);
        }

        Invoice invoice = Invoice.builder()
                .invoiceNumber(nextNumber.invoiceNumber())
                .invoiceCode(nextNumber.invoiceCode())
                .invoiceDate(invoiceDate)
                .dueDate(dueDate)
                .isPaid(false)
                .paidAmount(BigDecimal.ZERO)
                .dealer(dealer)
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .dealerTaxCode(dealer.getTaxCode())
                .dealerAddress(dealer.getAddress())
                .dealerPhone(dealer.getPhone())
                .status(InvoiceStatus.ISSUED)
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .createdBy(username != null ? username : "system")
                .createdAt(LocalDateTime.now())
                .build();

        BigDecimal totalSubtotal = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Order order : orders) {
            invoice.addOrder(order);

            for (OrderItem orderItem : order.getItems()) {
                BigDecimal deliveredQty = orderItem.getDeliveredQuantity();
                if (deliveredQty == null || deliveredQty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                BigDecimal unitPrice = orderItem.getPrice() != null ? orderItem.getPrice() : BigDecimal.ZERO;
                BigDecimal lineSubtotal = deliveredQty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);

                BigDecimal discountAmount = BigDecimal.ZERO;
                if (orderItem.getDiscountPercent() != null && orderItem.getDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
                    discountAmount = lineSubtotal.multiply(orderItem.getDiscountPercent())
                            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                } else if (orderItem.getDiscountAmount() != null && orderItem.getQuantity() != null && orderItem.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    discountAmount = orderItem.getDiscountAmount().multiply(deliveredQty)
                            .divide(orderItem.getQuantity(), 2, RoundingMode.HALF_UP);
                }

                BigDecimal lineTotal = lineSubtotal.subtract(discountAmount);

                totalSubtotal = totalSubtotal.add(lineSubtotal);
                totalDiscount = totalDiscount.add(discountAmount);
                totalAmount = totalAmount.add(lineTotal);

                InvoiceItem invoiceItem = InvoiceItem.builder()
                        .order(order)
                        .orderCode(order.getOrderCode())
                        .orderItemId(orderItem.getId())
                        .product(orderItem.getProduct())
                        .productCode(orderItem.getProduct().getCode())
                        .productName(orderItem.getProduct().getName())
                        .unit(orderItem.getUnit())
                        .orderedQuantity(orderItem.getQuantity())
                        .deliveredQuantity(deliveredQty)
                        .invoicedQuantity(deliveredQty) // Tiêu chí S7-01: đúng số hàng thực giao
                        .unitPrice(unitPrice)
                        .subtotalAmount(lineSubtotal)
                        .discountPercent(orderItem.getDiscountPercent())
                        .discountAmount(discountAmount)
                        .totalAmount(lineTotal)
                        .note(orderItem.getDeliveryNote())
                        .build();

                invoice.addItem(invoiceItem);
            }
        }

        if (invoice.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Các phiếu giao đã chọn không có mặt hàng nào được giao thực tế");
        }

        invoice.setSubtotalAmount(totalSubtotal);
        invoice.setDiscountAmount(totalDiscount);
        invoice.setTotalAmount(totalAmount);
        invoice.setAdjustedTotalAmount(totalAmount);

        Invoice saved = invoiceRepository.save(invoice);

        auditLogService.logAction(
                "INVOICE",
                saved.getInvoiceCode(),
                "ISSUE_INVOICE",
                null,
                saved.getStatus().name(),
                "Phát hành hóa đơn " + saved.getInvoiceCode() + " cho đại lý " + dealer.getCode() + " từ " + orders.size() + " phiếu giao",
                username != null ? username : "system"
        );

        return mapToInvoiceResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponse getById(Long id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hóa đơn với ID: " + id));
        return mapToInvoiceResponse(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceResponse> search(Long dealerId, InvoiceStatus status, LocalDate startDate, LocalDate endDate, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size), Sort.by(Sort.Direction.DESC, "id"));

        Specification<Invoice> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (dealerId != null) {
                predicates.add(cb.equal(root.get("dealer").get("id"), dealerId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), startDate.atStartOfDay()));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), endDate.atTime(LocalTime.MAX)));
            }
            if (keyword != null && !keyword.isBlank()) {
                String searchPattern = "%" + keyword.trim().toLowerCase() + "%";
                Predicate matchNumber = cb.like(cb.lower(root.get("invoiceNumber")), searchPattern);
                Predicate matchCode = cb.like(cb.lower(root.get("invoiceCode")), searchPattern);
                Predicate matchDealerName = cb.like(cb.lower(root.get("dealerName")), searchPattern);
                Predicate matchDealerCode = cb.like(cb.lower(root.get("dealerCode")), searchPattern);
                predicates.add(cb.or(matchNumber, matchCode, matchDealerName, matchDealerCode));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Invoice> invoices = invoiceRepository.findAll(spec, pageable);
        Map<Long, Map<Long, InvoiceResponse>> dealerAllocationCache = new HashMap<>();
        return invoices.map(inv -> mapToInvoiceResponseWithCache(inv, dealerAllocationCache));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceResponse> getDealerInvoices(Long dealerId, InvoiceStatus status, String paymentStatus,
                                                   LocalDate startDate, LocalDate endDate, String keyword, int page, int size) {
        if (dealerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID đại lý không được để trống");
        }
        List<InvoiceResponse> allDealerInvoices = getDealerInvoiceList(dealerId);

        List<InvoiceResponse> filtered = allDealerInvoices.stream()
                .filter(inv -> status == null || inv.getStatus() == status)
                .filter(inv -> {
                    if (paymentStatus == null || paymentStatus.isBlank()) {
                        return true;
                    }
                    return paymentStatus.trim().equalsIgnoreCase(inv.getPaymentStatus());
                })
                .filter(inv -> {
                    if (startDate == null || inv.getInvoiceDate() == null) {
                        return true;
                    }
                    return !inv.getInvoiceDate().toLocalDate().isBefore(startDate);
                })
                .filter(inv -> {
                    if (endDate == null || inv.getInvoiceDate() == null) {
                        return true;
                    }
                    return !inv.getInvoiceDate().toLocalDate().isAfter(endDate);
                })
                .filter(inv -> {
                    if (keyword == null || keyword.isBlank()) {
                        return true;
                    }
                    String q = keyword.trim().toLowerCase();
                    boolean matchCode = inv.getInvoiceCode() != null && inv.getInvoiceCode().toLowerCase().contains(q);
                    boolean matchNumber = inv.getInvoiceNumber() != null && inv.getInvoiceNumber().toLowerCase().contains(q);
                    boolean matchNote = inv.getNote() != null && inv.getNote().toLowerCase().contains(q);
                    return matchCode || matchNumber || matchNote;
                })
                .collect(Collectors.toList());

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        int fromIndex = Math.min(safePage * safeSize, filtered.size());
        int toIndex = Math.min(fromIndex + safeSize, filtered.size());
        List<InvoiceResponse> pageContent = filtered.subList(fromIndex, toIndex);

        return new PageImpl<>(pageContent, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "invoiceDate", "id")), filtered.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceResponse> getDealerInvoiceList(Long dealerId) {
        if (dealerId == null) {
            return Collections.emptyList();
        }
        List<Invoice> ascInvoices = invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(dealerId);
        if (ascInvoices == null || ascInvoices.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, InvoiceResponse> allocated = allocateDealerInvoicesFifo(dealerId, ascInvoices);

        List<Invoice> descInvoices = new ArrayList<>(ascInvoices);
        descInvoices.sort(Comparator
                .comparing(Invoice::getInvoiceDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Invoice::getId, Comparator.nullsLast(Comparator.reverseOrder())));

        return descInvoices.stream()
                .map(inv -> allocated.containsKey(inv.getId()) ? allocated.get(inv.getId()) : buildBaseInvoiceResponse(inv, BigDecimal.ZERO))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponse getDealerInvoiceById(Long dealerId, Long invoiceId) {
        if (dealerId == null) {
            throw new AccessDeniedException("Yêu cầu thông tin đại lý hợp lệ");
        }
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hóa đơn với ID: " + invoiceId));

        if (invoice.getDealer() == null || !dealerId.equals(invoice.getDealer().getId())) {
            throw new AccessDeniedException("Bạn chỉ có quyền xem và tải hóa đơn của chính đại lý mình");
        }
        return mapToInvoiceResponse(invoice);
    }

    @Override
    public void updateInvoice(Long id) {
        // Tiêu chí S7-01: Hóa đơn đã phát hành không sửa và không xóa, chỉ lập chứng từ điều chỉnh
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hóa đơn đã phát hành không được phép chỉnh sửa. Vui lòng lập chứng từ điều chỉnh.");
    }

    @Override
    public void deleteInvoice(Long id) {
        // Tiêu chí S7-01: Hóa đơn đã phát hành không sửa và không xóa, chỉ lập chứng từ điều chỉnh
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hóa đơn đã phát hành không được phép xóa. Vui lòng lập chứng từ điều chỉnh.");
    }

    @Override
    @Transactional
    public InvoiceAdjustmentResponse createAdjustment(Long invoiceId, CreateInvoiceAdjustmentRequest request, String username) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hóa đơn với ID: " + invoiceId));

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể lập chứng từ điều chỉnh cho hóa đơn đã bị hủy");
        }

        if (request.getAdjustmentType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại điều chỉnh không được để trống");
        }

        if (request.getAdjustmentReason() == null || request.getAdjustmentReason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lý do điều chỉnh không được để trống (bắt buộc theo quy định hóa đơn)");
        }

        long adjustmentSeq = adjustmentRepository.countByInvoiceId(invoice.getId()) + 1;
        String adjustmentCode = String.format("DC-%s-%02d", invoice.getInvoiceNumber(), adjustmentSeq);

        BigDecimal adjAmount = request.getAdjustmentAmount() != null ? request.getAdjustmentAmount() : BigDecimal.ZERO;
        if (request.getAdjustmentType() == InvoiceAdjustmentType.DECREASE && adjAmount.compareTo(BigDecimal.ZERO) > 0) {
            adjAmount = adjAmount.negate();
        } else if (request.getAdjustmentType() == InvoiceAdjustmentType.CANCEL) {
            adjAmount = invoice.getAdjustedTotalAmount().negate();
        } else if (request.getAdjustmentType() == InvoiceAdjustmentType.INFORMATION) {
            adjAmount = BigDecimal.ZERO;
        }

        InvoiceAdjustment adjustment = InvoiceAdjustment.builder()
                .adjustmentCode(adjustmentCode)
                .invoice(invoice)
                .adjustmentType(request.getAdjustmentType())
                .adjustmentReason(request.getAdjustmentReason().trim())
                .adjustmentDate(LocalDateTime.now())
                .adjustmentAmount(adjAmount)
                .newDealerName(request.getNewDealerName())
                .newDealerTaxCode(request.getNewDealerTaxCode())
                .newDealerAddress(request.getNewDealerAddress())
                .adjustedBy(username != null ? username : "system")
                .createdAt(LocalDateTime.now())
                .note(request.getNote())
                .build();

        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (InvoiceAdjustmentItemRequest itemReq : request.getItems()) {
                Product prod = null;
                if (itemReq.getProductId() != null) {
                    prod = productRepository.findById(itemReq.getProductId()).orElse(null);
                }

                InvoiceAdjustmentItem item = InvoiceAdjustmentItem.builder()
                .product(prod)
                        .productCode(itemReq.getProductCode())
                        .productName(itemReq.getProductName())
                        .unit(itemReq.getUnit())
                        .adjustedQuantity(itemReq.getAdjustedQuantity())
                        .adjustedPrice(itemReq.getAdjustedPrice())
                        .adjustmentAmount(itemReq.getAdjustmentAmount() != null ? itemReq.getAdjustmentAmount() : BigDecimal.ZERO)
                        .reason(itemReq.getReason())
                        .build();

                adjustment.addItem(item);
            }
        }

        String oldStatus = invoice.getStatus().name();
        if (request.getAdjustmentType() == InvoiceAdjustmentType.CANCEL) {
            invoice.setStatus(InvoiceStatus.CANCELLED);
            invoice.setAdjustedTotalAmount(BigDecimal.ZERO);
        } else {
            invoice.setStatus(InvoiceStatus.ADJUSTED);
            BigDecimal newAdjusted = invoice.getAdjustedTotalAmount().add(adjAmount);
            invoice.setAdjustedTotalAmount(newAdjusted);
        }

        invoice.addAdjustment(adjustment);
        InvoiceAdjustment savedAdj = adjustmentRepository.save(adjustment);
        invoiceRepository.save(invoice);

        auditLogService.logAction(
                "INVOICE",
                invoice.getInvoiceCode(),
                "ADJUST_INVOICE",
                oldStatus,
                invoice.getStatus().name(),
                "Lập chứng từ điều chỉnh " + savedAdj.getAdjustmentCode() + " (" + savedAdj.getAdjustmentType().getDisplayName() + "): " + request.getAdjustmentReason(),
                username != null ? username : "system"
        );

        return mapToAdjustmentResponse(savedAdj);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceAdjustmentResponse> getAdjustments(Long invoiceId) {
        if (!invoiceRepository.existsById(invoiceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hóa đơn với ID: " + invoiceId);
        }
        return adjustmentRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
                .map(this::mapToAdjustmentResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EligibleDeliveryOrderResponse> getEligibleOrders(Long dealerId, LocalDate startDate, LocalDate endDate) {
        if (dealerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID đại lý không được để trống");
        }

        List<Order> orders = orderRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("dealer").get("id"), dealerId));
            predicates.add(cb.equal(root.get("status"), "DELIVERED"));

            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("actualDeliveryDate"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("actualDeliveryDate"), endDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        });

        if (orders.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        List<Long> invoicedOrderIds = invoiceRepository.findAlreadyInvoicedOrderIds(orderIds);
        Set<Long> invoicedSet = new HashSet<>(invoicedOrderIds);

        List<EligibleDeliveryOrderResponse> result = new ArrayList<>();
        for (Order o : orders) {
            if (invoicedSet.contains(o.getId())) {
                continue; // Bỏ qua đơn đã xuất hóa đơn
            }

            BigDecimal deliveredTotal = BigDecimal.ZERO;
            List<EligibleDeliveryOrderItemResponse> items = new ArrayList<>();

            for (OrderItem it : o.getItems()) {
                BigDecimal delQty = it.getDeliveredQuantity();
                if (delQty == null || delQty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal price = it.getPrice() != null ? it.getPrice() : BigDecimal.ZERO;
                BigDecimal sub = delQty.multiply(price).setScale(2, RoundingMode.HALF_UP);
                BigDecimal disc = BigDecimal.ZERO;
                if (it.getDiscountPercent() != null && it.getDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
                    disc = sub.multiply(it.getDiscountPercent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                } else if (it.getDiscountAmount() != null && it.getQuantity() != null && it.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    disc = it.getDiscountAmount().multiply(delQty).divide(it.getQuantity(), 2, RoundingMode.HALF_UP);
                }
                BigDecimal itemDelTotal = sub.subtract(disc);
                deliveredTotal = deliveredTotal.add(itemDelTotal);

                items.add(EligibleDeliveryOrderItemResponse.builder()
                        .orderItemId(it.getId())
                        .productId(it.getProduct().getId())
                        .productCode(it.getProduct().getCode())
                        .productName(it.getProduct().getName())
                        .unit(it.getUnit())
                        .orderedQuantity(it.getQuantity())
                        .deliveredQuantity(delQty)
                        .unitPrice(price)
                        .discountAmount(disc)
                        .deliveredTotalAmount(itemDelTotal)
                        .deliveryNote(it.getDeliveryNote())
                        .build());
            }

            if (!items.isEmpty()) {
                result.add(EligibleDeliveryOrderResponse.builder()
                        .orderId(o.getId())
                        .orderCode(o.getOrderCode())
                        .actualDeliveryDate(o.getActualDeliveryDate())
                        .deliveryNote(o.getDeliveryNote())
                        .orderedTotalAmount(o.getTotalAmount())
                        .deliveredTotalAmount(deliveredTotal)
                        .itemCount(items.size())
                        .items(items)
                        .build());
            }
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EligibleDealerResponse> getDealersWithEligibleOrders() {
        List<Order> deliveredOrders = orderRepository.findAll((root, query, cb) ->
                cb.equal(root.get("status"), "DELIVERED"));

        if (deliveredOrders.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> orderIds = deliveredOrders.stream().map(Order::getId).collect(Collectors.toList());
        List<Long> invoicedOrderIds = invoiceRepository.findAlreadyInvoicedOrderIds(orderIds);
        Set<Long> invoicedSet = new HashSet<>(invoicedOrderIds);

        Map<Dealer, Long> dealerUninvoicedCount = deliveredOrders.stream()
                .filter(o -> !invoicedSet.contains(o.getId()))
                .filter(o -> o.getDealer() != null)
                .collect(Collectors.groupingBy(Order::getDealer, Collectors.counting()));

        return dealerUninvoicedCount.entrySet().stream()
                .map(e -> EligibleDealerResponse.builder()
                        .dealerId(e.getKey().getId())
                        .dealerCode(e.getKey().getCode())
                        .dealerName(e.getKey().getName())
                        .dealerTaxCode(e.getKey().getTaxCode())
                        .dealerPhone(e.getKey().getPhone())
                        .dealerAddress(e.getKey().getAddress())
                        .uninvoicedDeliveryCount(e.getValue().intValue())
                        .build())
                .sorted(Comparator.comparing(EligibleDealerResponse::getDealerCode))
                .collect(Collectors.toList());
    }

    private void validateOrdersForInvoicing(Dealer dealer, List<Order> orders, List<Long> requestedOrderIds) {
        if (orders.size() != requestedOrderIds.size()) {
            Set<Long> foundIds = orders.stream().map(Order::getId).collect(Collectors.toSet());
            List<Long> missingIds = requestedOrderIds.stream().filter(id -> !foundIds.contains(id)).toList();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu giao với các ID: " + missingIds);
        }

        for (Order o : orders) {
            if (o.getDealer() == null || !o.getDealer().getId().equals(dealer.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Phiếu giao " + o.getOrderCode() + " không thuộc đại lý " + dealer.getCode() + ". Tất cả phiếu giao gộp phải thuộc cùng một đại lý.");
            }
            if (!"DELIVERED".equalsIgnoreCase(o.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Phiếu giao " + o.getOrderCode() + " đang ở trạng thái '" + o.getStatus() + "'. Chỉ được phát hành hóa đơn cho các phiếu đã giao hàng thành công (DELIVERED).");
            }
        }

        List<Long> alreadyInvoiced = invoiceRepository.findAlreadyInvoicedOrderIds(requestedOrderIds);
        if (!alreadyInvoiced.isEmpty()) {
            List<String> invoicedCodes = orders.stream()
                    .filter(o -> alreadyInvoiced.contains(o.getId()))
                    .map(Order::getOrderCode)
                    .toList();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Các phiếu giao sau đã được xuất hóa đơn trước đó: " + invoicedCodes);
        }
    }

    private InvoiceResponse mapToInvoiceResponse(Invoice invoice) {
        if (invoice.getDealer() != null && invoice.getDealer().getId() != null && invoiceRepository != null) {
            Long dealerId = invoice.getDealer().getId();
            List<Invoice> dealerInvoices = invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(dealerId);
            if (dealerInvoices != null && !dealerInvoices.isEmpty()) {
                Map<Long, InvoiceResponse> allocated = allocateDealerInvoicesFifo(dealerId, dealerInvoices);
                if (invoice.getId() != null && allocated.containsKey(invoice.getId())) {
                    return allocated.get(invoice.getId());
                }
            }
        }
        BigDecimal totalPaid = getTotalPaidByDealer(invoice.getDealer() != null ? invoice.getDealer().getId() : null);
        return buildBaseInvoiceResponse(invoice, totalPaid);
    }

    private InvoiceResponse mapToInvoiceResponseWithCache(
            Invoice invoice,
            Map<Long, Map<Long, InvoiceResponse>> dealerAllocationCache) {
        if (invoice.getDealer() != null && invoice.getDealer().getId() != null) {
            Long dealerId = invoice.getDealer().getId();
            Map<Long, InvoiceResponse> allocated = dealerAllocationCache.computeIfAbsent(dealerId, id -> {
                List<Invoice> dealerInvoices = invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(id);
                if (dealerInvoices == null || dealerInvoices.isEmpty()) {
                    return Collections.emptyMap();
                }
                return allocateDealerInvoicesFifo(id, dealerInvoices);
            });
            if (invoice.getId() != null && allocated.containsKey(invoice.getId())) {
                return allocated.get(invoice.getId());
            }
        }
        return mapToInvoiceResponse(invoice);
    }

    private Map<Long, InvoiceResponse> allocateDealerInvoicesFifo(Long dealerId, List<Invoice> ascInvoices) {
        BigDecimal remainingPool = getTotalPaidByDealer(dealerId);
        Map<Long, BigDecimal> credits = FinancialReturnCredits.byInvoice(returnOrderRepository, dealerId);
        Map<Long, InvoiceResponse> result = new LinkedHashMap<>();

        for (Invoice inv : ascInvoices) {
            BigDecimal effectiveAmount = FinancialReturnCredits.netInvoice(inv, credits);
            BigDecimal allocated = BigDecimal.ZERO;
            if (inv.getStatus() != InvoiceStatus.CANCELLED && effectiveAmount.compareTo(BigDecimal.ZERO) > 0 && remainingPool.compareTo(BigDecimal.ZERO) > 0) {
                allocated = remainingPool.min(effectiveAmount);
                remainingPool = remainingPool.subtract(allocated).max(BigDecimal.ZERO);
            }
            InvoiceResponse resp = buildBaseInvoiceResponse(inv, allocated, effectiveAmount);
            if (inv.getId() != null) {
                result.put(inv.getId(), resp);
            }
        }
        return result;
    }

    private BigDecimal getTotalPaidByDealer(Long dealerId) {
        if (dealerId == null || debtPaymentRepository == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = debtPaymentRepository.sumAmountByDealerId(dealerId);
        return sum != null && sum.compareTo(BigDecimal.ZERO) > 0 ? sum : BigDecimal.ZERO;
    }

    private BigDecimal resolveEffectiveInvoiceAmount(Invoice invoice) {
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return BigDecimal.ZERO;
        }
        BigDecimal credit = returnOrderRepository != null && invoice.getId() != null
                ? returnOrderRepository.confirmedInvoiceCredit(invoice.getId()) : BigDecimal.ZERO;
        return FinancialReturnCredits.netInvoice(invoice, credit == null || invoice.getId() == null
                ? Collections.emptyMap() : Collections.singletonMap(invoice.getId(), credit));
    }

    private LocalDate resolveDueDate(Invoice invoice) {
        if (invoice.getDueDate() != null) {
            return invoice.getDueDate();
        }
        LocalDate baseDate = invoice.getInvoiceDate() != null
                ? invoice.getInvoiceDate().toLocalDate()
                : (invoice.getCreatedAt() != null ? invoice.getCreatedAt().toLocalDate() : LocalDate.now());
        int maxDays = (invoice.getDealer() != null && invoice.getDealer().getMaxDebtDays() != null && invoice.getDealer().getMaxDebtDays() > 0)
                ? invoice.getDealer().getMaxDebtDays()
                : 0;
        return baseDate.plusDays(maxDays);
    }

    private InvoiceResponse buildBaseInvoiceResponse(Invoice invoice, BigDecimal allocatedPayment) {
        return buildBaseInvoiceResponse(invoice, allocatedPayment, resolveEffectiveInvoiceAmount(invoice));
    }

    private InvoiceResponse buildBaseInvoiceResponse(Invoice invoice, BigDecimal allocatedPayment, BigDecimal effectiveAmount) {
        List<InvoiceItemResponse> itemResponses = (invoice.getItems() == null ? Collections.<InvoiceItem>emptyList() : invoice.getItems()).stream()
                .map(it -> InvoiceItemResponse.builder()
                        .id(it.getId())
                        .orderId(it.getOrder() != null ? it.getOrder().getId() : null)
                        .orderCode(it.getOrderCode())
                        .orderItemId(it.getOrderItemId())
                        .productId(it.getProduct() != null ? it.getProduct().getId() : null)
                        .productCode(it.getProductCode())
                        .productName(it.getProductName())
                        .unit(it.getUnit())
                        .orderedQuantity(it.getOrderedQuantity())
                        .deliveredQuantity(it.getDeliveredQuantity())
                        .invoicedQuantity(it.getInvoicedQuantity())
                        .unitPrice(it.getUnitPrice())
                        .subtotalAmount(it.getSubtotalAmount())
                        .discountPercent(it.getDiscountPercent())
                        .discountAmount(it.getDiscountAmount())
                        .totalAmount(it.getTotalAmount())
                        .note(it.getNote())
                        .build())
                .collect(Collectors.toList());

        List<InvoiceOrderSummaryResponse> orderSummaries = (invoice.getOrders() == null ? Collections.<Order>emptyList() : invoice.getOrders()).stream()
                .map(o -> InvoiceOrderSummaryResponse.builder()
                        .orderId(o.getId())
                        .orderCode(o.getOrderCode())
                        .actualDeliveryDate(o.getActualDeliveryDate())
                        .deliveredTotalAmount(o.getTotalAmount())
                        .itemCount(o.getItems() != null ? o.getItems().size() : 0)
                        .build())
                .collect(Collectors.toList());

        List<InvoiceAdjustmentResponse> adjResponses = (invoice.getAdjustments() == null ? Collections.<InvoiceAdjustment>emptyList() : invoice.getAdjustments()).stream()
                .map(this::mapToAdjustmentResponse)
                .collect(Collectors.toList());

        LocalDate dueDate = resolveDueDate(invoice);
        BigDecimal paidAmount = BigDecimal.ZERO;
        BigDecimal remainingAmount = BigDecimal.ZERO;
        String paymentStatus;
        String paymentStatusLabel;
        boolean isOverdue = false;
        int overdueDays = 0;
        int daysUntilDue = 0;

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            paymentStatus = "CANCELLED";
            paymentStatusLabel = "Đã hủy";
        } else {
            BigDecimal safeAllocated = allocatedPayment != null && allocatedPayment.compareTo(BigDecimal.ZERO) > 0
                    ? allocatedPayment : BigDecimal.ZERO;
            paidAmount = safeAllocated.min(effectiveAmount);
            remainingAmount = effectiveAmount.subtract(paidAmount).max(BigDecimal.ZERO);

            LocalDate today = LocalDate.now();
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                paymentStatus = "PAID";
                paymentStatusLabel = "Đã thanh toán";
            } else if (dueDate != null && today.isAfter(dueDate)) {
                isOverdue = true;
                overdueDays = (int) ChronoUnit.DAYS.between(dueDate, today);
                paymentStatus = "OVERDUE";
                paymentStatusLabel = "Quá hạn thanh toán";
            } else {
                if (dueDate != null && !today.isAfter(dueDate)) {
                    daysUntilDue = (int) ChronoUnit.DAYS.between(today, dueDate);
                }
                if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
                    paymentStatus = "PARTIALLY_PAID";
                    paymentStatusLabel = "Thanh toán một phần";
                } else {
                    paymentStatus = "UNPAID";
                    paymentStatusLabel = "Chưa thanh toán";
                }
            }
        }

        return InvoiceResponse.builder()
                .id(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .invoiceCode(invoice.getInvoiceCode())
                .invoiceDate(invoice.getInvoiceDate())
                .dueDate(dueDate)
                .isPaid(Boolean.TRUE.equals(invoice.getIsPaid()))
                .status(invoice.getStatus())
                .statusLabel(invoice.getStatus() != null ? invoice.getStatus().getDisplayName() : null)
                .dealerId(invoice.getDealer() != null ? invoice.getDealer().getId() : null)
                .dealerCode(invoice.getDealerCode())
                .dealerName(invoice.getDealerName())
                .dealerTaxCode(invoice.getDealerTaxCode())
                .dealerAddress(invoice.getDealerAddress())
                .dealerPhone(invoice.getDealerPhone())
                .subtotalAmount(invoice.getSubtotalAmount())
                .discountAmount(invoice.getDiscountAmount())
                .totalAmount(invoice.getTotalAmount())
                .adjustedTotalAmount(invoice.getAdjustedTotalAmount())
                .paidAmount(paidAmount)
                .remainingAmount(remainingAmount)
                .paymentStatus(paymentStatus)
                .paymentStatusLabel(paymentStatusLabel)
                .isOverdue(isOverdue)
                .overdueDays(overdueDays)
                .daysUntilDue(daysUntilDue)
                .note(invoice.getNote())
                .createdBy(invoice.getCreatedBy())
                .createdAt(invoice.getCreatedAt())
                .items(itemResponses)
                .orders(orderSummaries)
                .adjustments(adjResponses)
                .build();
    }

    private InvoiceAdjustmentResponse mapToAdjustmentResponse(InvoiceAdjustment adj) {
        List<InvoiceAdjustmentItemResponse> itemResponses = (adj.getItems() == null ? Collections.<InvoiceAdjustmentItem>emptyList() : adj.getItems()).stream()
                .map(it -> InvoiceAdjustmentItemResponse.builder()
                        .id(it.getId())
                        .productId(it.getProduct() != null ? it.getProduct().getId() : null)
                        .productCode(it.getProductCode())
                        .productName(it.getProductName())
                        .unit(it.getUnit())
                        .adjustedQuantity(it.getAdjustedQuantity())
                        .adjustedPrice(it.getAdjustedPrice())
                        .adjustmentAmount(it.getAdjustmentAmount())
                        .reason(it.getReason())
                        .build())
                .collect(Collectors.toList());

        return InvoiceAdjustmentResponse.builder()
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
                .newDealerName(adj.getNewDealerName())
                .newDealerTaxCode(adj.getNewDealerTaxCode())
                .newDealerAddress(adj.getNewDealerAddress())
                .adjustedBy(adj.getAdjustedBy())
                .createdAt(adj.getCreatedAt())
                .note(adj.getNote())
                .items(itemResponses)
                .build();
    }
}
