package com.quanlybanhang.repository;

import com.quanlybanhang.model.DealerReturn;
import com.quanlybanhang.model.DealerReturnStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DealerReturnRepository extends JpaRepository<DealerReturn, Long>, JpaSpecificationExecutor<DealerReturn> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DealerReturn r WHERE r.id = :id")
    Optional<DealerReturn> findByIdForUpdate(@Param("id") Long id);

    Optional<DealerReturn> findByReturnCode(String returnCode);

    boolean existsByReturnCode(String returnCode);

    List<DealerReturn> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);

    List<DealerReturn> findByDealerIdOrderByCreatedAtDesc(Long dealerId);

    List<DealerReturn> findByStatusOrderByCreatedAtDesc(DealerReturnStatus status);
}

