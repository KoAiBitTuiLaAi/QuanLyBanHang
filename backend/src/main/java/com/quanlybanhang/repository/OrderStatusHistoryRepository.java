package com.quanlybanhang.repository;

import com.quanlybanhang.model.OrderStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAsc(Long orderId);

    List<OrderStatusHistory> findByOrderIdOrderByChangedAtDesc(Long orderId);
}

