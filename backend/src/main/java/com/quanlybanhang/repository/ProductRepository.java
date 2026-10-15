package com.quanlybanhang.repository;

import com.quanlybanhang.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByCode(String code);

    Optional<Product> findByCodeIgnoreCase(String code);

    boolean existsByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    List<Product> findByCategoryId(Long categoryId);

    List<Product> findByCategoryIdIn(Collection<Long> categoryIds);

    long countByCategoryId(Long categoryId);

    long countByCategoryIdIn(Collection<Long> categoryIds);

    /**
     * Doanh số thực sau chiết khấu, chỉ tính đơn đã ghi sổ. Không dùng cột revenue cũ.
     */
    @Query("SELECT SUM(i.totalAmount) FROM OrderItem i WHERE i.order.status = 'RECORDED' " +
            "AND i.product.category.id IN :categoryIds")
    BigDecimal sumRevenueByCategoryIds(@Param("categoryIds") Collection<Long> categoryIds);

    @Query("SELECT SUM(i.totalAmount) FROM OrderItem i WHERE i.order.status = 'RECORDED' " +
            "AND i.product.id = :productId")
    BigDecimal sumRevenueByProductId(@Param("productId") Long productId);

    interface ProductRevenue {
        Long getProductId();
        BigDecimal getRevenue();
    }

    @Query("SELECT i.product.id AS productId, SUM(i.totalAmount) AS revenue FROM OrderItem i " +
            "WHERE i.order.status = 'RECORDED' AND i.product.id IN :productIds GROUP BY i.product.id")
    List<ProductRevenue> sumRevenueByProductIds(@Param("productIds") Collection<Long> productIds);

    /**
     * Tìm danh sách sản phẩm theo danh sách mã SKU (không phân biệt chữ hoa/thường).
     */
    @Query("SELECT p FROM Product p WHERE UPPER(p.code) IN :codes")
    List<Product> findByCodeUpperIn(@Param("codes") Collection<String> codes);

    List<Product> findByActiveTrueOrderByNameAsc();
}
