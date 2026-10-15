package com.quanlybanhang.repository;

import com.quanlybanhang.model.WarehouseTransferItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WarehouseTransferItemRepository extends JpaRepository<WarehouseTransferItem, Long> {
    List<WarehouseTransferItem> findByTransferId(Long transferId);
}

