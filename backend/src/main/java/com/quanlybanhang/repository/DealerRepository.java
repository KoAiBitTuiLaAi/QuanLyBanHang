package com.quanlybanhang.repository;

import com.quanlybanhang.model.CustomerGroup;
import com.quanlybanhang.model.Dealer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DealerRepository extends JpaRepository<Dealer, Long> {

    Optional<Dealer> findByCode(String code);

    Optional<Dealer> findByCodeIgnoreCase(String code);

    boolean existsByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    List<Dealer> findByAssignedUserId(Long userId);

    long countByAssignedUserId(Long userId);

    long countByWarehouseId(Long warehouseId);

    List<Dealer> findByActiveTrueOrderByNameAsc();

    List<Dealer> findByAssignedUserIdAndActiveTrueOrderByNameAsc(Long userId);

    List<Dealer> findByHandoverRequiredTrueOrderByNameAsc();

    List<Dealer> findByAssignedUserIdAndTerritoryId(Long userId, Long territoryId);

    List<Dealer> findByAssignedUserIdAndIdIn(Long userId, List<Long> ids);
    List<Dealer> findByActiveTrueAndLockedFalseOrderByNameAsc();

    List<Dealer> findByCustomerGroupAndActiveTrue(CustomerGroup customerGroup);

    List<Dealer> findByDebtFlaggedTrueOrderByDebtFlaggedAtDesc();

    long countByDebtFlaggedTrue();

    @Query("SELECT d FROM Dealer d WHERE " +
           "(:keyword IS NULL OR :keyword = '' OR LOWER(d.code) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(d.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(d.taxCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(d.phone) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:customerGroup IS NULL OR d.customerGroup = :customerGroup) " +
           "AND (:territoryId IS NULL OR d.territory.id = :territoryId) " +
           "AND (:assignedUserId IS NULL OR d.assignedUser.id = :assignedUserId) " +
           "AND (:active IS NULL OR d.active = :active) " +
           "AND (:locked IS NULL OR d.locked = :locked)")
    Page<Dealer> searchDealers(
            @Param("keyword") String keyword,
            @Param("customerGroup") CustomerGroup customerGroup,
            @Param("territoryId") Long territoryId,
            @Param("assignedUserId") Long assignedUserId,
            @Param("active") Boolean active,
            @Param("locked") Boolean locked,
            Pageable pageable);

    default Page<Dealer> searchDealers(
            String keyword,
            CustomerGroup customerGroup,
            Long territoryId,
            Long assignedUserId,
            Boolean active,
            Pageable pageable) {
        return searchDealers(keyword, customerGroup, territoryId, assignedUserId, active, null, pageable);
    }
}
