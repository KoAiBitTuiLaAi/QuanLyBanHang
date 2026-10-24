package com.quanlybanhang.repository;

import com.quanlybanhang.model.DeliveryStopStatus;
import com.quanlybanhang.model.DeliveryTripStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeliveryTripStopRepository extends JpaRepository<DeliveryTripStop, Long> {

    List<DeliveryTripStop> findByTripIdOrderByStopOrderAsc(Long tripId);

    List<DeliveryTripStop> findByTripIdAndStatus(Long tripId, DeliveryStopStatus status);

    boolean existsByOrderId(Long orderId);
}

