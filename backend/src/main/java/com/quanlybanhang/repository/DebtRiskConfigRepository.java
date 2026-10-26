package com.quanlybanhang.repository;

import com.quanlybanhang.model.DebtRiskConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DebtRiskConfigRepository extends JpaRepository<DebtRiskConfig, Long> {
    Optional<DebtRiskConfig> findFirstByOrderByIdAsc();
}

