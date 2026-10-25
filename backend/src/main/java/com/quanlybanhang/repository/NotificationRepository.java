package com.quanlybanhang.repository;

import com.quanlybanhang.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByDealerIdOrderByCreatedAtDesc(Long dealerId, Pageable pageable);

    Page<Notification> findByDealerIdAndIsReadFalseOrderByCreatedAtDesc(Long dealerId, Pageable pageable);

    long countByDealerIdAndIsReadFalse(Long dealerId);

    Optional<Notification> findByIdAndDealerId(Long id, Long dealerId);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :readAt WHERE n.dealer.id = :dealerId AND n.isRead = false")
    int markAllAsReadByDealerId(@Param("dealerId") Long dealerId, @Param("readAt") LocalDateTime readAt);
}
