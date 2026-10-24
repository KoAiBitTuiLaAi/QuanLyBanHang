package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/** Quantities, units and route are snapshots; later catalogue changes cannot rewrite a pick. */
@Entity @Table(name = "picking_batch_items", uniqueConstraints = @UniqueConstraint(columnNames = {"batch_id", "order_item_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PickingBatchItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "batch_id", nullable = false) private PickingBatch batch;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(nullable = false) private Order order;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_item_id", nullable = false) private OrderItem orderItem;
    @Column(nullable = false, length = 50) private String orderCode;
    @Column(nullable = false, length = 50) private String productCode;
    @Column(nullable = false, length = 255) private String productName;
    @Column(nullable = false, length = 50) private String unit;
    @Column(nullable = false, length = 50) private String baseUnit;
    @Column(nullable = false, precision = 15, scale = 4) private BigDecimal quantity;
    @Column(nullable = false, precision = 12, scale = 4) private BigDecimal conversionFactor;
    @Column(precision = 15, scale = 4) private BigDecimal pickedQuantity;
    @Column(nullable = false) @Builder.Default private Boolean checked = false;
    @Column(length = 500) private String shortageReason;
    private Long locationId;
    @Column(length = 50) private String locationCode;
    @Column(nullable = false, length = 500) private String locationDisplay;
    @Column(nullable = false) private Integer routePosition;
}
