package com.quanlybanhang.model;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
@Entity @Table(name="cash_collections", indexes=@Index(name="idx_collection_employee_day", columnList="employee_id,collection_date"))
@Getter @Setter @NoArgsConstructor
public class CashCollection {
 public enum Status { PENDING, RECEIVED }
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private Long version;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="dealer_id",nullable=false) private Dealer dealer;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="employee_id",nullable=false) private User employee;
 // Historical route snapshot, unaffected by subsequent dealer reassignment.
 @Column(length=120) private String routeName;
 @Column(nullable=false,precision=19,scale=2) private BigDecimal amount;
 @Column(name="collection_date",nullable=false) private LocalDate collectionDate;
 @Column(length=500) private String note;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status=Status.PENDING;
 @Lob @Basic(fetch=FetchType.LAZY) @Column(nullable=false,columnDefinition="LONGBLOB") private byte[] receipt;
 @Column(nullable=false,length=30) private String receiptContentType;
 @Column(nullable=false) private LocalDateTime createdAt;
 private LocalDateTime receivedAt;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="received_by_id") private User receivedBy;
}