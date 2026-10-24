package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.DebtPaymentRequest;
import com.quanlybanhang.dto.request.DebtRiskConfigRequest;
import com.quanlybanhang.dto.request.OrderDebtCheckRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.DebtFlagAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

/**
 * Service quản lý công nợ, hạn mức, kiểm soát rủi ro nợ và tự động cảnh báo / khoá bán (S4-02 & S7-04).
 */
public interface DealerDebtService {

    /**
     * Lấy thông tin công nợ, hạn mức và nợ quá hạn của đại lý theo ID.
     * Áp dụng kiểm tra quyền cấp dòng nếu người gọi là nhân viên kinh doanh.
     */
    DealerDebtResponse getDealerDebtInfo(Long dealerId);

    /**
     * Lấy thông tin công nợ, hạn mức và nợ quá hạn của đối tượng đại lý đã nạp.
     */
    DealerDebtResponse getDealerDebtInfo(Dealer dealer);

    /**
     * Kiểm tra sơ bộ hạn mức và trạng thái nợ của đại lý khi đặt đơn mới.
     */
    OrderDebtCheckResponse checkOrderDebt(OrderDebtCheckRequest request);

    /**
     * Kiểm tra sơ bộ hạn mức và trạng thái nợ của đại lý theo ID và số tiền đơn mới.
     */
    OrderDebtCheckResponse checkOrderDebt(Long dealerId, BigDecimal orderAmount);

    /**
     * Kiểm tra đại lý có đủ điều kiện đặt đơn mới không (S4-02, S7-04).
     * Áp dụng theo cấu hình:
     * - Nếu bị khoá giao dịch (S3-07): chặn hoàn toàn.
     * - Nếu bị gắn cờ và cấu hình là BLOCK: ném DealerDebtBlockedException chặn tạo đơn.
     * - Nếu bị gắn cờ và cấu hình là REQUIRE_APPROVAL: không chặn tại đây, đơn sẽ chuyển sang cần duyệt.
     */
    void validateDealerCanCreateOrder(Dealer dealer);

    /**
     * Kiểm tra xem tổng đơn cộng công nợ hiện tại có vượt hạn mức công nợ hay không.
     */
    boolean checkProjectedDebtExceedsLimit(Dealer dealer, BigDecimal newOrderTotal, BigDecimal currentDebt);

    // ── TÍNH NĂNG S7-04: CẢNH BÁO TỰ ĐỘNG, KHOÁ BÁN & THU TIỀN ──

    /**
     * Đánh giá lại công nợ của một đại lý và tự động gắn/gỡ cờ rủi ro (S7-04).
     */
    FlaggedDealerResponse evaluateDealerDebt(Long dealerId);

    /**
     * Tác vụ đánh giá lại công nợ toàn bộ đại lý (S7-04).
     * Chạy định kỳ hàng ngày (scheduler) hoặc chạy theo yêu cầu.
     */
    DebtEvaluationSummaryResponse evaluateAllDealersDebt();

    /**
     * Ghi nhận thu tiền / thanh toán công nợ và đối trừ vào hoá đơn (S7-02, S7-04).
     * Cho phép chọn đối trừ vào hoá đơn cụ thể hoặc tự động đối trừ theo nguyên tắc hoá đơn cũ nhất trước (FIFO).
     * Không cho thu vượt tổng công nợ còn lại.
     */
    DebtPaymentResponse recordPayment(DebtPaymentRequest request, String username);

    /**
     * Lập bút toán điều chỉnh cho một phiếu thu đã ghi nhận (S7-02).
     * Đã ghi nhận thì không xoá, chỉ lập bút toán điều chỉnh.
     */
    DebtPaymentResponse adjustPayment(Long paymentId, com.quanlybanhang.dto.request.DebtPaymentAdjustmentRequest request, String username);

    /**
     * Lấy chi tiết phiếu thu tiền / bút toán điều chỉnh kèm danh sách đối trừ (S7-02).
     */
    DebtPaymentResponse getPaymentById(Long paymentId);

    /**
     * Lấy danh sách hoá đơn và tình trạng thanh toán của đại lý để biết chính xác đại lý còn nợ hoá đơn nào (S7-02).
     */
    List<DealerInvoiceResponse> getDealerInvoices(Long dealerId, String paymentStatusFilter);

    /**
     * Lấy danh sách các hoá đơn còn nợ của đại lý sắp xếp từ cũ nhất đến mới nhất (S7-02).
     */
    List<DealerInvoiceResponse> getDealerUnpaidInvoices(Long dealerId);

    /**
     * Xem trước đối trừ tự động theo nguyên tắc hoá đơn cũ nhất trước (S7-02).
     */
    PaymentAllocationPreviewResponse previewPaymentAllocation(Long dealerId, BigDecimal amount);

    /**
     * Lấy lịch sử các lần đối trừ vào một hoá đơn / đơn hàng cụ thể (S7-02).
     */
    List<DebtPaymentAllocationResponse> getOrderSettlements(Long orderId);

    /**
     * Lấy danh sách lịch sử các lần thu tiền của đại lý (S7-04).
     */
    List<DebtPaymentResponse> getPaymentHistory(Long dealerId);

    /**
     * Lấy danh sách phiếu thu tiền có phân trang (S7-04).
     */
    Page<DebtPaymentResponse> getPayments(Long dealerId, Pageable pageable);

    /**
     * Lấy cấu hình kiểm soát rủi ro công nợ hệ thống (S7-04).
     */
    DebtRiskConfigResponse getDebtRiskConfig();

    /**
     * Cập nhật cấu hình kiểm soát rủi ro công nợ hệ thống (S7-04).
     */
    DebtRiskConfigResponse updateDebtRiskConfig(DebtRiskConfigRequest request, String username);

    /**
     * Cập nhật cấu hình hành vi riêng cho từng đại lý khi bị gắn cờ (S7-04).
     */
    DealerResponse updateDealerActionConfig(Long dealerId, DebtFlagAction action, String username);

    /**
     * Lấy danh sách các đại lý đang bị gắn cờ rủi ro công nợ (S7-04).
     */
    List<FlaggedDealerResponse> getFlaggedDealers();

    /**
     * Xác định hành vi hiệu lực áp dụng cho đại lý khi bị gắn cờ (S7-04).
     */
    DebtFlagAction resolveEffectiveAction(Dealer dealer);
}
