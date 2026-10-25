package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;

/** A saved period statement; neither its period nor its financial snapshot can be edited. */
@Entity
@Table(name = "debt_reconciliations", indexes = {
        @Index(name = "idx_reconciliation_dealer_period", columnList = "dealer_id,period_from,period_to")})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DebtReconciliation {
    public enum Status { PENDING, CONFIRMED, DISPUTED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version private Long version;
    @Column(nullable = false, unique = true, length = 50, updatable = false)
    private String code;
    @Column(name = "request_key", nullable = false, unique = true, length = 36, updatable = false)
    private String requestKey;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dealer_id", nullable = false, updatable = false)
    private Dealer dealer;
    @Column(name = "period_from", nullable = false, updatable = false)
    private LocalDate from;
    @Column(name = "period_to", nullable = false, updatable = false)
    private LocalDate to;
    @Column(name = "snapshot_json", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String snapshotJson;
    @Column(nullable = false, updatable = false, length = 100)
    private String createdBy;
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status;
    @Column(length = 100) private String respondedBy;
    private LocalDateTime respondedAt;
    @Column(precision = 24, scale = 2) private BigDecimal claimedBalance;
    @Column(length = 500) private String responseReason;
}
