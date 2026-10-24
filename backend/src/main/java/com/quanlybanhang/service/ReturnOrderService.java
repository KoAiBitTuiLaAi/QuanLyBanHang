package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.CancelReturnOrderRequest;
import com.quanlybanhang.dto.request.CreateReturnOrderRequest;
import com.quanlybanhang.dto.response.ReturnOrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Service quan ly phieu tra hang (S8-02).
 *
 * Tieu chi nghiep vu:
 * 1. Phieu tra hang lien ket toi hoa don goc.
 * 2. Khi xac nhan, tu dong sinh InvoiceAdjustment(DECREASE) dung gia tren hoa don goc.
 * 3. Cong no va han muc kha dung cua dai ly duoc cap nhat ngay.
 * 4. Hoa don goc khong bi sua.
 */
public interface ReturnOrderService {

    /**
     * Tao phieu tra hang moi (trang thai DRAFT).
     */
    ReturnOrderResponse create(CreateReturnOrderRequest request, String username);

    /**
     * Lay chi tiet phieu tra hang theo ID.
     */
    ReturnOrderResponse getById(Long id);

    /**
     * Danh sach phieu tra hang theo hoa don.
     */
    List<ReturnOrderResponse> getByInvoiceId(Long invoiceId);

    /**
     * Tim kiem phan trang phieu tra hang.
     */
    Page<ReturnOrderResponse> search(Long dealerId, Long invoiceId, String status, Pageable pageable);

    /**
     * Xac nhan phieu tra hang (DRAFT -> CONFIRMED).
     * Tu dong:
     *   - Sinh InvoiceAdjustment(DECREASE) lien ket hoa don goc, gia lay tu hoa don.
     *   - Giu nguyen moi truong du lieu hoa don goc.
     *   - Goi DealerDebtService.evaluateDealerDebt() de cap nhat cong no ngay.
     * Hoa don goc khong bi sua.
     */
    ReturnOrderResponse confirm(Long id, String username);

    /**
     * Huy phieu tra hang (chi duoc khi con o DRAFT).
     */
    ReturnOrderResponse cancel(Long id, CancelReturnOrderRequest request, String username);
}