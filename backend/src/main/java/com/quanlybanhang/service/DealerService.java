package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.AssignDealerRequest;
import com.quanlybanhang.dto.request.BulkHandoverRequest;
import com.quanlybanhang.dto.request.DealerRequest;
import com.quanlybanhang.dto.response.BulkHandoverResponse;
import com.quanlybanhang.dto.response.DealerAssignmentHistoryResponse;
import com.quanlybanhang.dto.response.DealerOptionsResponse;
import com.quanlybanhang.dto.response.DealerResponse;
import com.quanlybanhang.model.CustomerGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface DealerService {

    Page<DealerResponse> getAllDealers(
            String keyword,
            CustomerGroup customerGroup,
            Long territoryId,
            Long assignedUserId,
            Boolean active,
            Boolean locked,
            Pageable pageable);

    default Page<DealerResponse> getAllDealers(
            String keyword,
            CustomerGroup customerGroup,
            Long territoryId,
            Long assignedUserId,
            Boolean active,
            Pageable pageable) {
        return getAllDealers(keyword, customerGroup, territoryId, assignedUserId, active, null, pageable);
    }

    List<DealerResponse> getActiveDealers();

    DealerResponse getDealerById(Long id);

    DealerResponse createDealer(DealerRequest request, String currentUsername);

    DealerResponse updateDealer(Long id, DealerRequest request, String currentUsername);

    DealerResponse updateStatus(Long id, boolean active, String currentUsername);

    /**
     * Khoá giao dịch với đại lý (S3-07).
     * Bắt buộc có lý do khoá.
     */
    DealerResponse lockDealer(Long id, String reason, String currentUsername);

    /**
     * Mở khoá giao dịch với đại lý (S3-07).
     */
    DealerResponse unlockDealer(Long id, String currentUsername);

    /**
     * Lấy danh sách đơn hàng đang dở dang của đại lý (S3-07).
     */
    List<com.quanlybanhang.dto.response.OrderResponse> getInProgressOrders(Long dealerId);

    void deleteDealer(Long id, String currentUsername);

    DealerOptionsResponse getDealerOptions();

    // ── Phân công & Chuyển giao địa bàn (S3-06) ──

    DealerResponse assignDealer(Long id, AssignDealerRequest request, String currentUsername);

    BulkHandoverResponse bulkHandover(BulkHandoverRequest request, String currentUsername);

    List<DealerResponse> getPendingHandoverDealers();

    Page<DealerAssignmentHistoryResponse> getHandoverHistory(
            Long fromUserId,
            Long toUserId,
            Long dealerId,
            Long territoryId,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable);

    List<DealerAssignmentHistoryResponse> getDealerAssignmentHistory(Long dealerId);

    /**
     * S4-04: Lấy lịch sử mua hàng của đại lý trong N tháng gần nhất kèm số lượng bình quân (mặc định 3 tháng).
     * @param dealerId ID đại lý
     * @param months Số tháng gần nhất (mặc định 3)
     * @param lastOrderOnly Chỉ lấy nhóm hàng trong đơn gần nhất
     */
    List<com.quanlybanhang.dto.response.DealerPurchaseHistoryResponse> getDealerPurchaseHistory(Long dealerId, int months, boolean lastOrderOnly);

    /**
     * S4-04: Lấy nhóm hàng đã mua trong đơn hàng gần nhất của đại lý để thêm nhanh vào đơn mới.
     */
    List<com.quanlybanhang.dto.response.DealerPurchaseHistoryResponse> getDealerLastOrderItems(Long dealerId);
}
