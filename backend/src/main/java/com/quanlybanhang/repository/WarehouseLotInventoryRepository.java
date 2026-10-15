package com.quanlybanhang.repository;

import com.quanlybanhang.model.WarehouseLotInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarehouseLotInventoryRepository extends JpaRepository<WarehouseLotInventory, Long> {

    Optional<WarehouseLotInventory> findByWarehouseIdAndProductIdAndLotNumber(Long warehouseId, Long productId, String lotNumber);

    List<WarehouseLotInventory> findByWarehouseIdAndProductId(Long warehouseId, Long productId);

    List<WarehouseLotInventory> findByWarehouseId(Long warehouseId);

    List<WarehouseLotInventory> findByProductId(Long productId);

    List<WarehouseLotInventory> findAllByOrderByUpdatedAtDesc();
}

