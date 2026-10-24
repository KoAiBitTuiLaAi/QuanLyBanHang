package com.quanlybanhang.repository;

import com.quanlybanhang.model.WarehouseVoucherItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.Collection;

@Repository
public interface WarehouseVoucherItemRepository extends JpaRepository<WarehouseVoucherItem, Long> {

    List<WarehouseVoucherItem> findByVoucherId(Long voucherId);

    List<WarehouseVoucherItem> findByProductId(Long productId);

    boolean existsByProductId(Long productId);

    @Query("SELECT DISTINCT i.product.id FROM WarehouseVoucherItem i WHERE i.product.id IN :productIds")
    Set<Long> findProductIdsWithTransactions(@Param("productIds") Collection<Long> productIds);

    /**
     * Lấy toàn bộ dòng phát sinh phiếu kho (nhập/xuất/điều chỉnh) cho một sản phẩm.
     * Chỉ lấy phiếu đã ghi sổ (status != 'DRAFT') trong phạm vi kho và thời gian cho phép.
     * Dùng cho thẻ kho S6-02.
     */
    @Query("SELECT i FROM WarehouseVoucherItem i " +
            "JOIN FETCH i.voucher v " +
            "JOIN FETCH v.warehouse w " +
            "JOIN FETCH i.product p " +
            "WHERE i.product.id = :productId " +
            "AND UPPER(v.status) IN ('RECORDED', 'CONFIRMED', 'ADJUSTED') " +
            "AND w.id IN :warehouseIds " +
            "AND (:fromDate IS NULL OR COALESCE(v.recordedAt, v.voucherDate) >= :fromDate) " +
            "AND (:toDate IS NULL OR COALESCE(v.recordedAt, v.voucherDate) <= :toDate) " +
            "ORDER BY COALESCE(v.recordedAt, v.voucherDate) ASC, v.id ASC, i.id ASC")
    List<WarehouseVoucherItem> findForStockCard(
            @Param("productId") Long productId,
            @Param("warehouseIds") Collection<Long> warehouseIds,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);
}
