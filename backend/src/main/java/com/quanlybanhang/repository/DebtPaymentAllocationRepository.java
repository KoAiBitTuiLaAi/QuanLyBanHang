package com.quanlybanhang.repository;

import com.quanlybanhang.model.DebtPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface DebtPaymentAllocationRepository extends JpaRepository<DebtPaymentAllocation, Long> {

    List<DebtPaymentAllocation> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);

    List<DebtPaymentAllocation> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM DebtPaymentAllocation a WHERE a.order.id = :orderId")
    BigDecimal sumAmountByOrderId(@Param("orderId") Long orderId);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM DebtPaymentAllocation a WHERE a.payment.id = :paymentId")
    BigDecimal sumAmountByPaymentId(@Param("paymentId") Long paymentId);
}
