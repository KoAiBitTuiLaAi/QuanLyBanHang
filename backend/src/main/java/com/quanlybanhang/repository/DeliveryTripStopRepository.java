package com.quanlybanhang.repository;

import com.quanlybanhang.model.DeliveryStopStatus;
import com.quanlybanhang.model.DeliveryTripStatus;
import com.quanlybanhang.model.DeliveryTripStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeliveryTripStopRepository extends JpaRepository<DeliveryTripStop, Long> {

    List<DeliveryTripStop> findByTripIdOrderByStopOrderAsc(Long tripId);

    List<DeliveryTripStop> findByTripIdAndStatus(Long tripId, DeliveryStopStatus status);

    boolean existsByOrderId(Long orderId);

    List<DeliveryTripStop> findByOrderId(Long orderId);

    List<DeliveryTripStop> findByOrderIdOrderByIdDesc(Long orderId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from DeliveryTripStop s where s.order.id = :orderId order by s.id")
    List<DeliveryTripStop> findByOrderIdForUpdate(@org.springframework.data.repository.query.Param("orderId") Long orderId);

    boolean existsByShipmentVoucherId(Long voucherId);

    boolean existsByOrderIdAndTrip_Status(Long orderId, DeliveryTripStatus status);
}
