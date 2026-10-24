package com.quanlybanhang.repository;

import com.quanlybanhang.model.DeliveryTripStopItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface DeliveryTripStopItemRepository extends JpaRepository<DeliveryTripStopItem,Long> {
    @Query("select i from DeliveryTripStopItem i join fetch i.stop s join fetch s.trip t join fetch t.warehouse w where i.product.id=:productId and w.id in :warehouseIds and s.returnedToWarehouse=true and s.returnedAt is not null order by s.returnedAt,s.id,i.id")
    List<DeliveryTripStopItem> findForStockCard(@Param("productId") Long productId,@Param("warehouseIds") Collection<Long> warehouseIds);
}
