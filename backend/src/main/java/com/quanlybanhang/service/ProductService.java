package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.ProductImportRowRequest;
import com.quanlybanhang.dto.request.ProductRequest;
import com.quanlybanhang.dto.response.ProductImportResponse;
import com.quanlybanhang.dto.response.ProductResponse;
import org.springframework.web.multipart.MultipartFile;

import com.quanlybanhang.dto.response.ProductPriceHistoryResponse;

import java.util.List;

public interface ProductService {

    ProductResponse create(ProductRequest request);

    ProductResponse update(Long id, ProductRequest request);

    ProductResponse updateStatus(Long id, boolean active);

    /**
     * Bật hoặc tắt quản lý lô theo từng SKU, có thể cấu hình kèm ngưỡng cảnh báo (S6-01).
     */
    ProductResponse toggleManageLot(Long id, Boolean manageLot, Integer expiryWarningDays);

    ProductResponse getById(Long id);

    List<ProductResponse> getAll(Long categoryId);

    List<ProductResponse> getAll(Long categoryId, String search, Boolean active);

    List<ProductPriceHistoryResponse> getPriceHistory(Long productId);

    void delete(Long id);

    /**
     * Sinh tệp Excel mẫu để người dùng tải về (S2-08).
     */
    byte[] generateImportTemplate();

    /**
     * Xem trước và kiểm tra tính hợp lệ từng dòng sản phẩm từ danh sách DTO (S2-08).
     */
    ProductImportResponse previewImport(List<ProductImportRowRequest> requests);

    /**
     * Xem trước và kiểm tra tính hợp lệ từng dòng sản phẩm từ tệp Excel tải lên (S2-08).
     */
    ProductImportResponse previewImport(MultipartFile file);

    /**
     * Nhập danh mục sản phẩm hàng loạt từ danh sách DTO vào DB (S2-08).
     * SKU đã tồn tại thì cập nhật thay vì tạo mới.
     */
    ProductImportResponse importProducts(List<ProductImportRowRequest> requests);

    /**
     * Nhập danh mục sản phẩm hàng loạt từ tệp Excel tải lên vào DB (S2-08).
     * SKU đã tồn tại thì cập nhật thay vì tạo mới.
     */
    ProductImportResponse importProducts(MultipartFile file);
}

