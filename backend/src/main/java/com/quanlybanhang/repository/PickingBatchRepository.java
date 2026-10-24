package com.quanlybanhang.repository;

import com.quanlybanhang.model.PickingBatch;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PickingBatchRepository extends JpaRepository<PickingBatch, Long>, JpaSpecificationExecutor<PickingBatch> {
    boolean existsByCode(String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select b from PickingBatch b where b.id = :id")
    Optional<PickingBatch> findByIdForUpdate(@Param("id") Long id);
}
