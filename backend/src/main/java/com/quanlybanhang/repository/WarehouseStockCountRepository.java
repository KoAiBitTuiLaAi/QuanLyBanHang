package com.quanlybanhang.repository;

import com.quanlybanhang.model.WarehouseStockCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WarehouseStockCountRepository extends JpaRepository<WarehouseStockCount, Long>, JpaSpecificationExecutor<WarehouseStockCount> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    // Fetch children in the locking read as well: MySQL REPEATABLE READ must not
    // initialize them later from an older snapshot after waiting for the header.
    @org.springframework.data.jpa.repository.Query("select sc from WarehouseStockCount sc left join fetch sc.items where sc.id = :id")
    Optional<WarehouseStockCount> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<WarehouseStockCount> findByCode(String code);
    boolean existsByCode(String code);
}
