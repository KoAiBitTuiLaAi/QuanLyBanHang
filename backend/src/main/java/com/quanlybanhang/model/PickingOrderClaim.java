package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

/** Unique DB claim prevents duplicate picks even under concurrent requests. Cancel releases it. */
@Entity @Table(name = "picking_order_claims")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PickingOrderClaim {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_id", nullable = false, unique = true) private Order order;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "batch_id", nullable = false) private PickingBatch batch;
}
