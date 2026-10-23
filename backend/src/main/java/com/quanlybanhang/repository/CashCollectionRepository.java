package com.quanlybanhang.repository;
import com.quanlybanhang.model.CashCollection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
public interface CashCollectionRepository extends JpaRepository<CashCollection,Long>, JpaSpecificationExecutor<CashCollection> {
 interface DailyTotals {
  Long getEmployeeId();
  String getEmployeeName();
  LocalDate getDate();
  BigDecimal getCollected();
  BigDecimal getReceived();
  Long getCount();
 }
 @Query("""
   select c.employee.id as employeeId, c.employee.fullName as employeeName,
          c.collectionDate as date, sum(c.amount) as collected,
          sum(case when c.status = :received then c.amount else 0 end) as received, count(c) as count
   from CashCollection c
   where c.collectionDate between :from and :to and (:employeeId is null or c.employee.id = :employeeId)
   group by c.employee.id, c.employee.fullName, c.collectionDate
   order by c.collectionDate, c.employee.id
   """)
 List<DailyTotals> dailyTotals(@Param("from") LocalDate from, @Param("to") LocalDate to,
   @Param("employeeId") Long employeeId, @Param("received") CashCollection.Status received);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select c from CashCollection c where c.id = :id")
 Optional<CashCollection> findForConfirmation(@Param("id") Long id);
}