package com.quanlybanhang.repository;

import com.quanlybanhang.model.WarehouseInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WarehouseInventoryRepository extends JpaRepository<WarehouseInventory, Long> {
    @Query(value = """
            select i from WarehouseInventory i join fetch i.product p
             where i.warehouse.id = :warehouseId and p.active = true
               and (:keyword is null or lower(p.code) like :keyword escape '!'
                    or lower(p.name) like :keyword escape '!')
             order by p.code, p.id
            """, countQuery = """
            select count(i) from WarehouseInventory i join i.product p
             where i.warehouse.id = :warehouseId and p.active = true
               and (:keyword is null or lower(p.code) like :keyword escape '!'
                    or lower(p.name) like :keyword escape '!')
            """)
    org.springframework.data.domain.Page<WarehouseInventory> findTransferStock(
            @Param("warehouseId") Long warehouseId, @Param("keyword") String keyword,
            org.springframework.data.domain.Pageable pageable);

    Optional<WarehouseInventory> findByWarehouseIdAndProductId(Long warehouseId, Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT wi FROM WarehouseInventory wi WHERE wi.warehouse.id = :warehouseId AND wi.product.id = :productId")
    Optional<WarehouseInventory> findByWarehouseIdAndProductIdForUpdate(@Param("warehouseId") Long warehouseId, @Param("productId") Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT wi FROM WarehouseInventory wi WHERE wi.warehouse.id = :warehouseId AND wi.product.id IN :productIds ORDER BY wi.product.id")
    List<WarehouseInventory> findByWarehouseIdAndProductIdInForUpdate(@Param("warehouseId") Long warehouseId, @Param("productIds") Collection<Long> productIds);

    /**
     * Giữ chỗ tồn theo điều kiện ngay tại database để chống hai giao dịch
     * cùng nhìn thấy một lượng tồn khả dụng rồi cùng đặt vượt tồn.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE WarehouseInventory wi
               SET wi.reservedQuantity = wi.reservedQuantity + :quantity
             WHERE wi.warehouse.id = :warehouseId
               AND wi.product.id = :productId
               AND wi.baseQuantity - wi.reservedQuantity >= :quantity
            """)
    int reserveIfAvailable(@Param("warehouseId") Long warehouseId,
                           @Param("productId") Long productId,
                           @Param("quantity") java.math.BigDecimal quantity);

    List<WarehouseInventory> findByWarehouseIdAndProductIdIn(Long warehouseId, Collection<Long> productIds);

    List<WarehouseInventory> findByWarehouseId(Long warehouseId);

    List<WarehouseInventory> findByProductId(Long productId);

    List<WarehouseInventory> findAllByOrderByProduct_CodeAsc();
}
