package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bảng quản lý dãy số hóa đơn bán hàng liên tục, không trùng, không nhảy cóc (S7-01).
 *
 * Sử dụng PESSIMISTIC_WRITE lock trong cùng transaction phát hành hóa đơn.
 * Nếu transaction rollback, bộ đếm không bị nhảy cóc (không như DB auto-increment).
 */
@Entity
@Table(name = "invoice_sequences", uniqueConstraints = {
        @UniqueConstraint(columnNames = "sequence_key")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sequence_key", nullable = false, unique = true, length = 50)
    private String sequenceKey;

    @Column(name = "current_value", nullable = false)
    @Builder.Default
    private Long currentValue = 0L;

    @Column(name = "prefix", length = 20)
    @Builder.Default
    private String prefix = "HD-";

    @Column(name = "padding_length", nullable = false)
    @Builder.Default
    private Integer paddingLength = 8;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

