package com.quanlybanhang.repository;

import com.quanlybanhang.model.WarehouseVoucher;
import com.quanlybanhang.model.WarehouseVoucherType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarehouseVoucherRepository extends JpaRepository<WarehouseVoucher, Long> {

    Optional<WarehouseVoucher> findByVoucherCode(String voucherCode);

    boolean existsByVoucherCode(String voucherCode);

    List<WarehouseVoucher> findByWarehouseIdOrderByRecordedAtDesc(Long warehouseId);

    List<WarehouseVoucher> findByTypeOrderByRecordedAtDesc(WarehouseVoucherType type);

    List<WarehouseVoucher> findAllByOrderByRecordedAtDesc();

    boolean existsBySupplierId(Long supplierId);

    @org.springframework.data.jpa.repository.Query("SELECT v FROM WarehouseVoucher v WHERE " +
            "(:warehouseId IS NULL OR v.warehouse.id = :warehouseId) AND " +
            "(:type IS NULL OR v.type = :type) AND " +
            "(:status IS NULL OR UPPER(v.status) = UPPER(:status)) AND " +
            "(:supplierId IS NULL OR (v.supplier IS NOT NULL AND v.supplier.id = :supplierId)) " +
            "ORDER BY COALESCE(v.recordedAt, v.voucherDate) DESC, v.id DESC")
    List<WarehouseVoucher> searchVouchers(
            @org.springframework.data.repository.query.Param("warehouseId") Long warehouseId,
            @org.springframework.data.repository.query.Param("type") WarehouseVoucherType type,
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("supplierId") Long supplierId);
}
