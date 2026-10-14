package com.quanlybanhang.exception;

import com.quanlybanhang.dto.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * Xử lý exception toàn cục - trả về JSON response nhất quán cho frontend.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Nội dung yêu cầu phải là JSON hợp lệ và không được để trống"));
    }

    /**
     * Xử lý lỗi đăng nhập sai mật khẩu/username.
     * Luôn trả message chung — không tiết lộ tài khoản có tồn tại hay không.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Xử lý khoá tạm do đăng nhập sai quá nhiều lần (S1-01).
     * Trả về 423 Locked kèm thời gian còn lại.
     */
    @ExceptionHandler(AccountTemporarilyLockedException.class)
    public ResponseEntity<ApiResponse> handleTemporaryLock(AccountTemporarilyLockedException ex) {
        Map<String, Object> data = new HashMap<>();
        data.put("remainingMinutes", ex.getRemainingMinutes());
        data.put("type", "TEMPORARY_LOCK");

        return ResponseEntity.status(HttpStatus.LOCKED)  // 423
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(data)
                        .build());
    }

    /**
     * Xử lý tài khoản bị khóa bởi admin (S1-10).
     */
    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiResponse> handleLocked(LockedException ex) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "ADMIN_LOCK");

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.builder()
                        .success(false)
                        .message("Tài khoản đã bị khóa. Vui lòng liên hệ quản trị viên.")
                        .data(data)
                        .build());
    }

    /**
     * Xử lý tài khoản bị vô hiệu hóa.
     */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiResponse> handleDisabled(DisabledException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Tài khoản đã bị vô hiệu hóa."));
    }

    /**
     * Xử lý lỗi validation.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        return ResponseEntity.badRequest()
                .body(ApiResponse.builder()
                        .success(false)
                        .message("Dữ liệu không hợp lệ")
                        .data(errors)
                        .build());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("Kích thước tệp tải lên vượt quá giới hạn cho phép."));
    }

    /**
     * Xử lý lỗi không thể xóa nhóm hàng khi còn sản phẩm (S2-06).
     */
    @ExceptionHandler(CategoryNotEmptyException.class)
    public ResponseEntity<ApiResponse> handleCategoryNotEmpty(CategoryNotEmptyException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Xử lý lỗi không thể xóa sản phẩm đã phát sinh giao dịch (S2-05).
     */
    @ExceptionHandler(ProductHasTransactionsException.class)
    public ResponseEntity<ApiResponse> handleProductHasTransactions(ProductHasTransactionsException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Xử lý lỗi bảng giá đã phát sinh đơn hàng không được sửa/xóa (S2-10).
     */
    @ExceptionHandler(PriceListLockedException.class)
    public ResponseEntity<ApiResponse> handlePriceListLocked(PriceListLockedException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Xử lý lỗi không thể xóa đại lý đã phát sinh giao dịch (S3-03).
     */
    @ExceptionHandler(DealerHasTransactionsException.class)
    public ResponseEntity<ApiResponse> handleDealerHasTransactions(DealerHasTransactionsException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Xử lý lỗi đại lý bị khoá không tạo được đơn mới (S3-07).
     */
    @ExceptionHandler(DealerLockedException.class)
    public ResponseEntity<ApiResponse> handleDealerLocked(DealerLockedException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Xử lý lỗi đại lý có nợ quá hạn quá số ngày cho phép bị chặn tạo đơn (S4-02).
     */
    @ExceptionHandler(DealerDebtOverdueException.class)
    public ResponseEntity<ApiResponse> handleDealerDebtOverdue(DealerDebtOverdueException ex) {
        Map<String, Object> details = new HashMap<>();
        details.put("dealerCode", ex.getDealerCode());
        details.put("dealerName", ex.getDealerName());
        details.put("overdueDays", ex.getOverdueDays());
        details.put("maxDebtDays", ex.getMaxDebtDays());
        details.put("overdueAmount", ex.getOverdueAmount());
        return ResponseEntity.badRequest()
                .body(ApiResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .data(details)
                        .build());
    }

    /**
     * Xử lý lỗi đơn hàng đã xuất kho không thể huỷ (S4-06).
     */
    @ExceptionHandler(OrderCannotBeCancelledException.class)
    public ResponseEntity<ApiResponse> handleOrderCannotBeCancelled(OrderCannotBeCancelledException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Xử lý lỗi không có quyền truy cập (@PreAuthorize hoặc AccessDeniedException).
     * Trả về 403 Forbidden.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Bạn không có quyền thực hiện chức năng này"));
    }

    /**
     * Xử lý các RuntimeException chung.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse> handleRuntimeException(RuntimeException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Xử lý tất cả exception không bắt được.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleGenericException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau."));
    }
}
