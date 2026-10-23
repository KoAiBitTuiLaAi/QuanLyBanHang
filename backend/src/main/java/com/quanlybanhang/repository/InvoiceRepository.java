package com.quanlybanhang.repository;

import com.quanlybanhang.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    Optional<Invoice> findByInvoiceCode(String invoiceCode);

    boolean existsByInvoiceNumber(String invoiceNumber);

    boolean existsByInvoiceCode(String invoiceCode);

    List<Invoice> findByDealerIdOrderByInvoiceDateAscIdAsc(Long dealerId);

    List<Invoice> findByDealerIdOrderByInvoiceDateDescIdDesc(Long dealerId);

    @Query("SELECT o.id FROM Invoice i JOIN i.orders o WHERE o.id IN :orderIds AND i.status != com.quanlybanhang.model.InvoiceStatus.CANCELLED")
    List<Long> findAlreadyInvoicedOrderIds(@Param("orderIds") List<Long> orderIds);

    @Query("SELECT COUNT(i) > 0 FROM Invoice i JOIN i.orders o WHERE o.id = :orderId AND i.status != com.quanlybanhang.model.InvoiceStatus.CANCELLED")
    boolean isOrderInvoiced(@Param("orderId") Long orderId);
}
