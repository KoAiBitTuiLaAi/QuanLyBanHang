package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String entityType; // e.g., INVENTORY, PRICE, DEBT_LIMIT, INVOICE

    @Column(nullable = false, length = 100)
    private String entityId;

    @Column(nullable = false, length = 100)
    private String action; // CREATE, UPDATE, DELETE

    @Column(columnDefinition = "TEXT")
    private String oldValue;

    @Column(columnDefinition = "TEXT")
    private String newValue;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
