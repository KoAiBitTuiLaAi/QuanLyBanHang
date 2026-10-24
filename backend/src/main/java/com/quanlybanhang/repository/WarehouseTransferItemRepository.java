package com.quanlybanhang.repository;

import com.quanlybanhang.model.WarehouseTransferItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface WarehouseTransferItemRepository extends JpaRepository<WarehouseTransferItem, Long> {
    List<WarehouseTransferItem> findByTransferId(Long transferId);

    /**
     * Lấy toàn bộ dòng chuyển kho của một sản phẩm trong phạm vi kho và thời gian cho phép.
     * Bao gồm phiếu IN_TRANSIT (chưa nhận) và COMPLETED (đã nhận) và CANCELLED (đã hủy).
     * Dùng cho thẻ kho S6-02.
     */
    @Query("SELECT i FROM WarehouseTransferItem i " +
            "JOIN FETCH i.transfer t " +
            "JOIN FETCH t.fromWarehouse fw " +
            "JOIN FETCH t.toWarehouse tw " +
            "JOIN FETCH i.product p " +
            "WHERE i.product.id = :productId " +
            "AND (fw.id IN :warehouseIds OR tw.id IN :warehouseIds) " +
            "AND (:fromDate IS NULL OR t.dispatchedAt >= :fromDate " +
            "   OR (t.receivedAt IS NOT NULL AND t.receivedAt >= :fromDate) " +
            "   OR (t.cancelledAt IS NOT NULL AND t.cancelledAt >= :fromDate)) " +
            "AND (:toDate IS NULL OR t.dispatchedAt <= :toDate " +
            "   OR (t.receivedAt IS NOT NULL AND t.receivedAt <= :toDate) " +
            "   OR (t.cancelledAt IS NOT NULL AND t.cancelledAt <= :toDate)) " +
            "ORDER BY t.dispatchedAt ASC, t.id ASC, i.id ASC")
    List<WarehouseTransferItem> findForStockCard(
            @Param("productId") Long productId,
            @Param("warehouseIds") Collection<Long> warehouseIds,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);
}
