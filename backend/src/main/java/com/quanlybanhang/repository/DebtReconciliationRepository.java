package com.quanlybanhang.repository;

import com.quanlybanhang.model.DebtReconciliation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface DebtReconciliationRepository extends JpaRepository<DebtReconciliation, Long>,
        JpaSpecificationExecutor<DebtReconciliation> {
    Optional<DebtReconciliation> findByRequestKey(String requestKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DebtReconciliation r where r.id = :id")
    Optional<DebtReconciliation> findForUpdate(@Param("id") Long id);
}
