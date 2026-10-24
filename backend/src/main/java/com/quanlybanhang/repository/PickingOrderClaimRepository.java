package com.quanlybanhang.repository;
import com.quanlybanhang.model.PickingOrderClaim;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickingOrderClaimRepository extends JpaRepository<PickingOrderClaim, Long> {
    boolean existsByOrderId(Long orderId);
    void deleteAllByBatchId(Long batchId);
}
