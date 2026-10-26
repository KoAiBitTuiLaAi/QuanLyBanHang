package com.quanlybanhang.repository;

import com.quanlybanhang.model.WarehouseStockCountItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WarehouseStockCountItemRepository extends JpaRepository<WarehouseStockCountItem, Long> {
    @org.springframework.data.jpa.repository.Query("select i from WarehouseStockCountItem i join fetch i.stockCount c join fetch c.warehouse w where i.product.id=:productId and w.id in :warehouseIds and c.status=com.quanlybanhang.model.StockCountStatus.COMPLETED order by c.completedAt,c.id,i.id")
    List<WarehouseStockCountItem> findForStockCard(@org.springframework.data.repository.query.Param("productId") Long productId,
            @org.springframework.data.repository.query.Param("warehouseIds") java.util.Collection<Long> warehouseIds);
    List<WarehouseStockCountItem> findByStockCountId(Long stockCountId);
}
