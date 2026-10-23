package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.*;

/** S6-03: a persisted walk through one warehouse, potentially serving many orders. */
@Entity @Table(name = "picking_batches")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PickingBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private Long version;
    @Column(nullable = false, unique = true, length = 50) private String code;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(nullable = false) private Warehouse warehouse;
    @Column(nullable = false, length = 20) private String status;
    @Column(length = 500) private String note;
    @Column(length = 500) private String cancelReason;
    @Column(nullable = false, length = 100) private String createdBy;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("routePosition ASC") @Builder.Default private List<PickingBatchItem> items = new ArrayList<>();
}
