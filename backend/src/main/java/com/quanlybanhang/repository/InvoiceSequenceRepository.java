package com.quanlybanhang.repository;

import com.quanlybanhang.model.InvoiceSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InvoiceSequence s WHERE s.sequenceKey = :key")
    Optional<InvoiceSequence> findBySequenceKeyForUpdate(@Param("key") String key);

    Optional<InvoiceSequence> findBySequenceKey(String sequenceKey);
}

