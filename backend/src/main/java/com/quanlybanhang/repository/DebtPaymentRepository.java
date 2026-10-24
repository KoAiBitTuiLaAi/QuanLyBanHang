package com.quanlybanhang.repository;

import com.quanlybanhang.model.DebtPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface DebtPaymentRepository extends JpaRepository<DebtPayment, Long> {

    List<DebtPayment> findByDealerIdOrderByPaymentDateDescCreatedAtDesc(Long dealerId);

    Page<DebtPayment> findByDealerId(Long dealerId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM DebtPayment p WHERE p.dealer.id = :dealerId")
    BigDecimal sumAmountByDealerId(@Param("dealerId") Long dealerId);

    boolean existsByPaymentCode(String paymentCode);

    long countByPaymentDate(LocalDate paymentDate);

    long countByPaymentCodeStartingWith(String prefix);

    List<DebtPayment> findByOriginalPaymentId(Long originalPaymentId);

    boolean existsByOriginalPaymentIdAndPaymentType(Long originalPaymentId, String paymentType);
}

