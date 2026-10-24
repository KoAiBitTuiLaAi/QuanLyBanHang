package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.ProductImportRowRequest;
import com.quanlybanhang.dto.request.ProductRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.ProductImportResponse;
import com.quanlybanhang.dto.response.ProductResponse;
import com.quanlybanhang.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Controller Quản lý danh mục sản phẩm (S2-05).
 *
 * Yêu cầu nghiệp vụ:
 * - Khai báo mã SKU, tên, nhóm hàng, đơn vị tính cơ sở, quy cách đóng gói, giá vốn, ảnh, trạng thái
 * - Mã SKU là duy nhất
 * - Giá vốn chỉ Quản lý kinh doanh xem và sửa được
 * - Sản phẩm đã phát sinh giao dịch thì không xoá được, chỉ ngừng kinh doanh
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final Path imageStoragePath = Paths.get("./data/products").toAbsolutePath().normalize();

    // Dữ liệu tham chiếu tối thiểu cho tạo đơn, bảng giá và kho; không cấp quyền
    // đọc toàn bộ hồ sơ sản phẩm hoặc làm lộ giá vốn/doanh thu.
    private static final String LOOKUP_PERMISSION = "hasRole('ADMIN') or hasAnyAuthority('PRODUCT_VIEW', "
            + "'PRODUCT_CREATE', 'PRODUCT_EDIT', 'ORDER_CREATE', 'WAREHOUSE_VIEW', "
            + "'WAREHOUSE_MANAGE', 'WAREHOUSE_IMPORT', 'WAREHOUSE_EXPORT', 'WAREHOUSE_APPROVE', 'WAREHOUSE_ADJUST')";

    @GetMapping("/lookup")
    @PreAuthorize(LOOKUP_PERMISSION)
    public ApiResponse lookupProducts(@RequestParam(required = false) String search,
                                     @RequestParam(required = false) Boolean active) {
        List<ProductResponse> rows = search != null || active != null
                ? productService.getAll(null, search, active) : productService.getAll(null);
        return ApiResponse.success("Danh mục tham chiếu sản phẩm", rows.stream().map(this::lookupProduct).toList());
    }

    @GetMapping("/lookup/{id}")
    @PreAuthorize(LOOKUP_PERMISSION)
    public ApiResponse lookupProductById(@PathVariable Long id) {
        return ApiResponse.success("Sản phẩm tham chiếu", lookupProduct(productService.getById(id)));
    }

    private ProductResponse lookupProduct(ProductResponse product) {
        return ProductResponse.builder().id(product.getId()).code(product.getCode()).sku(product.getSku())
                .name(product.getName()).categoryId(product.getCategoryId()).categoryName(product.getCategoryName())
                .unit(product.getUnit()).price(product.getPrice()).active(product.getActive()).manageLot(product.getManageLot())
                .packingSpecification(product.getPackingSpecification()).imageUrl(product.getImageUrl()).build();
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_VIEW'))")
    public ApiResponse getAllProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active) {
        List<ProductResponse> products = (search != null || active != null)
                ? productService.getAll(categoryId, search, active)
                : productService.getAll(categoryId);
        return ApiResponse.success("Danh sách sản phẩm", products);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_VIEW'))")
    public ApiResponse getProductById(@PathVariable Long id) {
        return ApiResponse.success("Chi tiết sản phẩm", productService.getById(id));
    }

    @GetMapping("/{id}/price-history")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('FINANCE_VIEW_COST_PRICE'))")
    public ApiResponse getProductPriceHistory(@PathVariable Long id) {
        return ApiResponse.success("Lịch sử thay đổi giá", productService.getPriceHistory(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_CREATE'))")
    public ResponseEntity<ApiResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        ProductResponse response = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo sản phẩm thành công", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_EDIT'))")
    public ApiResponse updateProduct(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        ProductResponse response = productService.update(id, request);
        return ApiResponse.success("Cập nhật sản phẩm thành công", response);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_EDIT'))")
    public ApiResponse updateProductStatus(@PathVariable Long id, @RequestParam boolean active) {
        ProductResponse response = productService.updateStatus(id, active);
        String msg = active ? "Đã kích hoạt kinh doanh sản phẩm" : "Đã chuyển sản phẩm sang ngừng kinh doanh";
        return ApiResponse.success(msg, response);
    }

    /**
     * API bật/tắt quản lý lô và cập nhật ngưỡng cảnh báo hạn sử dụng theo từng SKU (S6-01).
     */
    @PatchMapping("/{id}/manage-lot")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('PRODUCT_EDIT', 'WAREHOUSE_MANAGE')")
    public ApiResponse toggleManageLot(
            @PathVariable Long id,
            @Valid @RequestBody com.quanlybanhang.dto.request.ManageLotToggleRequest request) {
        ProductResponse response = productService.toggleManageLot(id, request.getManageLot(), request.getExpiryWarningDays());
        String msg = Boolean.TRUE.equals(request.getManageLot())
                ? "Đã bật quản lý lô cho sản phẩm"
                : "Đã tắt quản lý lô cho sản phẩm";
        return ApiResponse.success(msg, response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_DELETE'))")
    public ApiResponse deleteProduct(@PathVariable Long id) {
        productService.delete(id);
        return ApiResponse.success("Xóa sản phẩm thành công", null);
    }

    /**
     * Tải tệp Excel mẫu để nhập danh mục sản phẩm hàng loạt (S2-08).
     */
    @GetMapping("/import/template")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('PRODUCT_CREATE', 'PRODUCT_EDIT'))")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        byte[] excelBytes = productService.generateImportTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mau_nhap_san_pham.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }

    /**
     * Xem trước và kiểm tra lỗi theo từng dòng từ tệp Excel tải lên (S2-08).
     * SKU đã tồn tại trong hệ thống được đánh dấu rõ action = "UPDATE", isUpdate = true.
     */
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_CREATE') and hasAuthority('PRODUCT_EDIT'))")
    public ApiResponse previewImportFile(@RequestParam("file") MultipartFile file) {
        ProductImportResponse result = productService.previewImport(file);
        return ApiResponse.success("Kết quả kiểm tra tệp nhập sản phẩm", result);
    }

    /**
     * Xem trước và kiểm tra lỗi theo từng dòng từ dữ liệu JSON (S2-08).
     */
    @PostMapping(value = "/import/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_CREATE') and hasAuthority('PRODUCT_EDIT'))")
    public ApiResponse previewImportJson(@RequestBody List<ProductImportRowRequest> rows) {
        ProductImportResponse result = productService.previewImport(rows);
        return ApiResponse.success("Kết quả kiểm tra dữ liệu nhập sản phẩm", result);
    }

    /**
     * Nhập danh mục sản phẩm hàng loạt từ tệp Excel vào cơ sở dữ liệu (S2-08).
     * SKU đã tồn tại thì cập nhật thay vì tạo mới.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_CREATE') and hasAuthority('PRODUCT_EDIT'))")
    public ApiResponse importProductsFile(@RequestParam("file") MultipartFile file) {
        ProductImportResponse result = productService.importProducts(file);
        return ApiResponse.success("Đã nhập thành công " + result.getImportedRows() + "/" + result.getTotalRows()
                + " sản phẩm (" + result.getCreateCount() + " tạo mới, " + result.getUpdateCount() + " cập nhật)", result);
    }

    /**
     * Nhập danh mục sản phẩm hàng loạt từ mảng JSON vào cơ sở dữ liệu (S2-08).
     * SKU đã tồn tại thì cập nhật thay vì tạo mới.
     */
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_CREATE') and hasAuthority('PRODUCT_EDIT'))")
    public ApiResponse importProductsJson(@RequestBody List<ProductImportRowRequest> rows) {
        ProductImportResponse result = productService.importProducts(rows);
        return ApiResponse.success("Đã nhập thành công " + result.getImportedRows() + "/" + result.getTotalRows()
                + " sản phẩm (" + result.getCreateCount() + " tạo mới, " + result.getUpdateCount() + " cập nhật)", result);
    }

    /**
     * Upload ảnh sản phẩm (S2-05).
     */
    @PostMapping(value = "/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('PRODUCT_CREATE', 'PRODUCT_EDIT'))")
    public ResponseEntity<ApiResponse> uploadProductImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Tập tin ảnh không được để trống"));
        }

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }

        Set<String> allowedExtensions = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");
        if (!allowedExtensions.contains(extension)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Định dạng ảnh không hợp lệ (hỗ trợ JPG, PNG, WEBP, GIF)"));
        }

        if (file.getSize() > 5 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Kích thước ảnh tối đa là 5MB"));
        }

        try {
            if (!Files.exists(imageStoragePath)) {
                Files.createDirectories(imageStoragePath);
            }

            String newFileName = UUID.randomUUID() + extension;
            Path targetLocation = imageStoragePath.resolve(newFileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            String fileUrl = "/products/images/" + newFileName;
            return ResponseEntity.ok(ApiResponse.success("Tải ảnh lên thành công", Map.of(
                    "imageUrl", fileUrl,
                    "fileName", newFileName
            )));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Không thể lưu tập tin ảnh: " + e.getMessage()));
        }
    }

    /**
     * Xem / Tải ảnh sản phẩm đã lưu.
     */
    @GetMapping("/images/{fileName:.+}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('PRODUCT_VIEW'))")
    public ResponseEntity<Resource> getProductImage(@PathVariable String fileName) {
        try {
            Path filePath = imageStoragePath.resolve(fileName).normalize();
            if (!filePath.startsWith(imageStoragePath)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() && resource.isReadable()) {
                String contentType = Files.probeContentType(filePath);
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }

                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}

