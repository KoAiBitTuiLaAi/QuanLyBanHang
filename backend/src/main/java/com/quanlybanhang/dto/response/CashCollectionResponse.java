package com.quanlybanhang.dto.response;
import com.quanlybanhang.model.CashCollection;
import java.math.BigDecimal;
import java.time.*;
public record CashCollectionResponse(Long id,Long dealerId,String dealerName,Long employeeId,String employeeName,
 String routeName,BigDecimal amount,LocalDate collectionDate,String note,CashCollection.Status status,
 LocalDateTime createdAt,LocalDateTime receivedAt,String receivedBy,String receiptUrl) {
 public static CashCollectionResponse from(CashCollection c) {
  return new CashCollectionResponse(c.getId(),c.getDealer().getId(),c.getDealer().getName(),c.getEmployee().getId(),
   c.getEmployee().getFullName(),c.getRouteName(),c.getAmount(),c.getCollectionDate(),c.getNote(),c.getStatus(),
   c.getCreatedAt(),c.getReceivedAt(),c.getReceivedBy()==null?null:c.getReceivedBy().getUsername(),
   "/api/finance/cash-collections/"+c.getId()+"/receipt");
 }
}