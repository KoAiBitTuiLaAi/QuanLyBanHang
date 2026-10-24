package com.quanlybanhang.repository;

import com.quanlybanhang.model.PaymentReminderLog;
import com.quanlybanhang.model.PaymentReminderType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PaymentReminderLogRepository extends JpaRepository<PaymentReminderLog, Long>, JpaSpecificationExecutor<PaymentReminderLog> {

    /**
     * Kiểm tra xem hóa đơn này đã được gửi nhắc trong ngày hôm đó hay chưa (chống gửi trùng).
     */
    boolean existsByInvoiceIdAndReminderDate(Long invoiceId, LocalDate reminderDate);

    /**
     * Kiểm tra xem hóa đơn đã từng được gửi loại nhắc này hay chưa (vd: đã từng gửi BEFORE_DUE_3_DAYS).
     */
    boolean existsByInvoiceIdAndReminderType(Long invoiceId, PaymentReminderType reminderType);

    Page<PaymentReminderLog> findByDealerIdOrderBySentAtDesc(Long dealerId, Pageable pageable);

    List<PaymentReminderLog> findByInvoiceIdOrderBySentAtDesc(Long invoiceId);

    Page<PaymentReminderLog> findAllByOrderBySentAtDesc(Pageable pageable);
}
