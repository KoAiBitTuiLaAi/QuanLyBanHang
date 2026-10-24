package com.quanlybanhang.repository;

import com.quanlybanhang.model.InvoiceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceAdjustmentRepository extends JpaRepository<InvoiceAdjustment, Long> {

    Optional<InvoiceAdjustment> findByAdjustmentCode(String adjustmentCode);

    List<InvoiceAdjustment> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);

    long countByInvoiceId(Long invoiceId);
}

