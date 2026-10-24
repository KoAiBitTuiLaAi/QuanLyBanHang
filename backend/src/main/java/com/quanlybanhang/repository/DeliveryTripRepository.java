package com.quanlybanhang.repository;

import com.quanlybanhang.model.DeliveryTrip;
import com.quanlybanhang.model.DeliveryTripStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryTripRepository extends JpaRepository<DeliveryTrip, Long>, JpaSpecificationExecutor<DeliveryTrip> {

    Optional<DeliveryTrip> findByTripCode(String tripCode);

    boolean existsByTripCode(String tripCode);

    List<DeliveryTrip> findAllByWarehouseIdInAndStatus(Collection<Long> warehouseIds, DeliveryTripStatus status);

    List<DeliveryTrip> findAllByStatus(DeliveryTripStatus status);

    @Query("SELECT dt FROM DeliveryTrip dt WHERE dt.warehouse.id IN :warehouseIds " +
           "AND dt.status = :status AND dt.expectedCompletionTime < :now")
    List<DeliveryTrip> findOverdueTrips(
            @Param("warehouseIds") Collection<Long> warehouseIds,
            @Param("status") DeliveryTripStatus status,
            @Param("now") LocalDateTime now);

    @Query("SELECT dt FROM DeliveryTrip dt WHERE dt.warehouse.id IN :warehouseIds")
    List<DeliveryTrip> findAllByWarehouseIdIn(@Param("warehouseIds") Collection<Long> warehouseIds);
}

