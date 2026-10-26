package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.ProductImportRowRequest;
import com.quanlybanhang.dto.request.ProductRequest;
import com.quanlybanhang.dto.response.ProductImportResponse;
import com.quanlybanhang.dto.response.ProductImportRowResponse;
import com.quanlybanhang.dto.response.ProductResponse;
import com.quanlybanhang.exception.ProductHasTransactionsException;
import com.quanlybanhang.model.Category;
import com.quanlybanhang.model.Permission;
import com.quanlybanhang.model.Product;
import com.quanlybanhang.model.ProductPriceHistory;
import com.quanlybanhang.repository.CategoryRepository;
import com.quanlybanhang.repository.ProductPriceHistoryRepository;
import com.quanlybanhang.repository.ProductRepository;
import com.quanlybanhang.repository.OrderItemRepository;
import com.quanlybanhang.repository.WarehouseVoucherItemRepository;
import com.quanlybanhang.dto.response.ProductPriceHistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service quản lý danh mục sản phẩm (S2-05 & S2-08).
 * Tuân thủ các quy tắc:
 * 1. Khai báo mã SKU, tên, nhóm hàng, đơn vị tính cơ sở, quy cách đóng gói, giá vốn, ảnh, trạng thái
 * 2. Mã SKU là duy nhất
 * 3. Giá vốn CHỈ Quản lý kinh doanh (ROLE_SALES_MANAGER) xem và sửa được
 * 4. Sản phẩm đã phát sinh giao dịch thì không xoá được, chỉ ngừng kinh doanh
 * 5. Hỗ trợ nhập danh mục sản phẩm hàng loạt từ Excel (S2-08): tải tệp mẫu, xem trước và báo lỗi từng dòng,
 *    cập nhật sản phẩm nếu SKU đã tồn tại và đánh dấu rõ trong bản xem trước.
 */
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    private final com.quanlybanhang.repository.WarehouseLotInventoryRepository lotInventoryRepository;

    private void validateLotSettings(Product product, Boolean manageLot, Integer warningDays) {
        if (warningDays != null && warningDays < 1) throw new IllegalArgumentException("Ngưỡng cảnh báo phải ít nhất 1 ngày");
        if (Boolean.FALSE.equals(manageLot)
                && lotInventoryRepository.existsByProductIdAndBaseQuantityGreaterThan(product.getId(), BigDecimal.ZERO))
            throw new IllegalArgumentException("SKU còn tồn theo lô. Cần xử lý hết tồn lô trước khi tắt quản lý lô để không bỏ qua kiểm tra hạn sử dụng.");
    }
    private final CategoryRepository categoryRepository;
    private final ProductPriceHistoryRepository productPriceHistoryRepository;
    private final AuditLogService auditLogService;
    private final ProductExcelService productExcelService;
    private final OrderItemRepository orderItemRepository;
    private final WarehouseVoucherItemRepository warehouseVoucherItemRepository;

    @Override
    @Transactional
    public ProductResponse create(ProductRequest request) {
        String sku = request.getEffectiveSku();
        if (sku.isBlank()) {
            throw new IllegalArgumentException("Mã SKU không được để trống");
        }
        sku = sku.toUpperCase();

        if (productRepository.existsByCodeIgnoreCase(sku)) {
            throw new IllegalArgumentException("Mã SKU '" + sku + "' đã tồn tại");
        }

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhóm hàng với ID: " + request.getCategoryId()));

        boolean canManageCostPrice = isSalesManager();
        BigDecimal costPrice = (canManageCostPrice && request.getCostPrice() != null)
                ? request.getCostPrice()
                : BigDecimal.ZERO;

        BigDecimal price = request.getPrice() != null ? request.getPrice() : BigDecimal.ZERO;

        Product product = Product.builder()
                .code(sku)
                .name(request.getName().trim())
                .description(request.getDescription())
                .category(category)
                .price(price)
                .costPrice(costPrice)
                .unit(request.getUnit() != null && !request.getUnit().isBlank() ? request.getUnit().trim() : "cái")
                .packingSpecification(request.getPackingSpecification() != null ? request.getPackingSpecification().trim() : null)
                .imageUrl(request.getImageUrl() != null ? request.getImageUrl().trim() : null)
                .active(request.getActive() != null ? request.getActive() : true)
                .manageLot(request.getManageLot() != null ? request.getManageLot() : false)
                .expiryWarningDays(request.getExpiryWarningDays() != null ? request.getExpiryWarningDays() : 30)
                .build();

        if (request.getUnitConversions() != null && !request.getUnitConversions().isEmpty()) {
            for (com.quanlybanhang.dto.request.UnitConversionRequest ucReq : request.getUnitConversions()) {
                if (ucReq.getUnitName() != null && !ucReq.getUnitName().isBlank() && ucReq.getConversionFactor() != null) {
                    product.addUnitConversion(com.quanlybanhang.model.UnitConversion.builder()
                            .product(product)
                            .unitName(ucReq.getUnitName().trim())
                            .conversionFactor(ucReq.getConversionFactor())
                            .isBaseUnit(false)
                            .price(ucReq.getPrice())
                            .costPrice(canManageCostPrice ? ucReq.getCostPrice() : null)
                            .barcode(ucReq.getBarcode())
                            .description(ucReq.getDescription())
                            .build());
                }
            }
        }

        Product saved = productRepository.save(product);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + id));

        String sku = request.getEffectiveSku();
        if (sku.isBlank()) {
            throw new IllegalArgumentException("Mã SKU không được để trống");
        }
        sku = sku.toUpperCase();

        if (productRepository.existsByCodeIgnoreCaseAndIdNot(sku, id)) {
            throw new IllegalArgumentException("Mã SKU '" + sku + "' đã được sử dụng");
        }

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhóm hàng với ID: " + request.getCategoryId()));

        product.setCode(sku);
        product.setName(request.getName().trim());
        product.setDescription(request.getDescription());
        product.setCategory(category);

        if (request.getPrice() != null) {
            if (product.getPrice() == null || product.getPrice().compareTo(request.getPrice()) != 0) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                String username = authentication != null ? authentication.getName() : "SYSTEM";

                ProductPriceHistory history = ProductPriceHistory.builder()
                        .product(product)
                        .oldPrice(product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO)
                        .newPrice(request.getPrice())
                        .modifiedBy(username)
                        .build();
                productPriceHistoryRepository.save(history);

                auditLogService.logAction(
                        "PRICE",
                        product.getId().toString(),
                        "UPDATE",
                        product.getPrice() != null ? product.getPrice().toString() : null,
                        request.getPrice().toString(),
                        username
                );
            }
            product.setPrice(request.getPrice());
        }

        // Quy tắc: Giá vốn chỉ Quản lý kinh doanh được phép sửa
        if (isSalesManager()) {
            if (request.getCostPrice() != null) {
                product.setCostPrice(request.getCostPrice());
            }
        }
        // Nếu không phải Quản lý kinh doanh thì giữ nguyên giá vốn hiện tại

        if (request.getUnit() != null && !request.getUnit().isBlank()) {
            product.setUnit(request.getUnit().trim());
        }
        if (request.getPackingSpecification() != null) {
            product.setPackingSpecification(request.getPackingSpecification().trim());
        }
        if (request.getImageUrl() != null) {
            product.setImageUrl(request.getImageUrl().trim());
        }
        if (request.getActive() != null) {
            product.setActive(request.getActive());
        }
        validateLotSettings(product, request.getManageLot(), request.getExpiryWarningDays());
        if (request.getManageLot() != null) {
            product.setManageLot(request.getManageLot());
        }
        if (request.getExpiryWarningDays() != null) {
            product.setExpiryWarningDays(request.getExpiryWarningDays());
        }

        Product saved = productRepository.save(product);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public ProductResponse toggleManageLot(Long id, Boolean manageLot, Integer expiryWarningDays) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + id));
        validateLotSettings(product, manageLot, expiryWarningDays);
        if (manageLot != null) {
            product.setManageLot(manageLot);
        }
        if (expiryWarningDays != null) {
            product.setExpiryWarningDays(expiryWarningDays);
        }
        Product saved = productRepository.save(product);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public ProductResponse updateStatus(Long id, boolean active) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + id));
        product.setActive(active);
        Product saved = productRepository.save(product);
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + id));
        return mapToResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> getAll(Long categoryId) {
        return getAll(categoryId, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> getAll(Long categoryId, String search, Boolean active) {
        List<Product> products;
        if (categoryId != null) {
            products = productRepository.findByCategoryId(categoryId);
        } else {
            products = productRepository.findAll();
        }

        List<Product> filtered = products.stream()
                .filter(p -> {
                    if (active != null && !active.equals(p.getActive())) {
                        return false;
                    }
                    if (search != null && !search.isBlank()) {
                        String q = search.trim().toLowerCase();
                        boolean matchSku = p.getCode() != null && p.getCode().toLowerCase().contains(q);
                        boolean matchName = p.getName() != null && p.getName().toLowerCase().contains(q);
                        return matchSku || matchName;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        return mapToResponses(filtered);
    }

    private List<ProductResponse> mapToResponses(List<Product> products) {
        if (products.isEmpty()) return List.of();
        // Tổng hợp theo lô để danh mục lớn không phát sinh truy vấn thống kê cho từng SKU.
        List<Long> ids = products.stream().map(Product::getId).filter(Objects::nonNull).toList();
        Map<Long, BigDecimal> revenues = ids.isEmpty() ? Map.of() : productRepository.sumRevenueByProductIds(ids).stream()
                .collect(Collectors.toMap(ProductRepository.ProductRevenue::getProductId,
                        ProductRepository.ProductRevenue::getRevenue));
        Set<Long> transactionIds = new HashSet<>();
        if (!ids.isEmpty()) {
            transactionIds.addAll(orderItemRepository.findProductIdsWithTransactions(ids));
            transactionIds.addAll(warehouseVoucherItemRepository.findProductIdsWithTransactions(ids));
        }
        return products.stream().map(product -> mapToResponse(product,
                product.getId() == null ? BigDecimal.ZERO : revenues.getOrDefault(product.getId(), BigDecimal.ZERO),
                transactionIds.contains(product.getId())))
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + id));

        // Quy tắc S2-05: Sản phẩm đã phát sinh giao dịch thì không xoá được, chỉ ngừng kinh doanh
        if (hasRecordedTransactions(product.getId())) {
            throw new ProductHasTransactionsException(product.getCode(), product.getName());
        }

        productRepository.delete(product);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateImportTemplate() {
        List<Category> categories = categoryRepository.findAllByOrderByLevelAscNameAsc();
        return productExcelService.generateTemplate(categories);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductImportResponse previewImport(MultipartFile file) {
        validateUploadFile(file);
        try {
            List<ProductImportRowRequest> requests = productExcelService.parseExcel(file.getInputStream());
            return previewImport(requests);
        } catch (IOException e) {
            throw new IllegalArgumentException("Không thể đọc nội dung tệp Excel: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ProductImportResponse previewImport(List<ProductImportRowRequest> requests) {
        List<ProductImportRowRequest> safeRequests = requests == null ? List.of() : requests;
        if (safeRequests.isEmpty()) {
            return ProductImportResponse.builder()
                    .totalRows(0)
                    .validRows(0)
                    .invalidRows(0)
                    .createCount(0)
                    .updateCount(0)
                    .importedRows(0)
                    .rows(List.of())
                    .build();
        }

        // Cache danh mục nhóm hàng
        List<Category> allCategories = categoryRepository.findAll();
        Map<Long, Category> categoryById = new HashMap<>();
        Map<String, Category> categoryByCode = new HashMap<>();
        Map<String, Category> categoryByName = new HashMap<>();
        for (Category cat : allCategories) {
            categoryById.put(cat.getId(), cat);
            if (cat.getCode() != null) categoryByCode.put(cat.getCode().trim().toUpperCase(Locale.ROOT), cat);
            if (cat.getName() != null) categoryByName.put(cat.getName().trim().toUpperCase(Locale.ROOT), cat);
        }

        // Trích xuất danh sách SKU để truy vấn hàng loạt
        Set<String> uniqueSkus = new HashSet<>();
        for (ProductImportRowRequest req : safeRequests) {
            if (req == null) continue;
            String sku = req.getEffectiveSku();
            if (!sku.isBlank()) {
                uniqueSkus.add(sku.trim().toUpperCase(Locale.ROOT));
            }
        }

        Map<String, Product> existingProductMap = fetchExistingProductsMap(uniqueSkus);
        boolean canManageCostPrice = isSalesManager();

        Map<String, Integer> seenSkisInFile = new HashMap<>();
        List<ProductImportRowResponse> rowResponses = new ArrayList<>();
        int createCount = 0;
        int updateCount = 0;

        for (int i = 0; i < safeRequests.size(); i++) {
            ProductImportRowRequest req = safeRequests.get(i);
            if (req == null) req = new ProductImportRowRequest();
            int rowNum = req.getRowNumber() != null ? req.getRowNumber() : (i + 2);
            String rawSku = req.getEffectiveSku();
            String normalizedSku = rawSku.trim().toUpperCase(Locale.ROOT);
            List<String> errors = req.getParseErrors() != null ? new ArrayList<>(req.getParseErrors()) : new ArrayList<>();

            // 1. Kiểm tra SKU
            if (normalizedSku.isEmpty()) {
                errors.add("Mã SKU không được để trống");
            } else if (normalizedSku.length() > 50) {
                errors.add("Mã SKU tối đa 50 ký tự");
            } else {
                if (seenSkisInFile.containsKey(normalizedSku)) {
                    errors.add("Mã SKU '" + normalizedSku + "' bị trùng lặp với dòng " + seenSkisInFile.get(normalizedSku) + " trong tệp");
                } else {
                    seenSkisInFile.put(normalizedSku, rowNum);
                }
            }

            // 2. Kiểm tra tên sản phẩm
            if (req.getName() == null || req.getName().trim().isEmpty()) {
                errors.add("Tên sản phẩm không được để trống");
            } else if (req.getName().trim().length() > 255) {
                errors.add("Tên sản phẩm tối đa 255 ký tự");
            }

            // 3. Khớp nhóm hàng (theo ID, mã nhóm hoặc tên nhóm)
            Category resolvedCat = null;
            if (req.getCategoryId() != null) {
                resolvedCat = categoryById.get(req.getCategoryId());
            }
            if (resolvedCat == null && req.getCategory() != null && !req.getCategory().trim().isEmpty()) {
                String catKey = req.getCategory().trim().toUpperCase(Locale.ROOT);
                resolvedCat = categoryByCode.get(catKey);
                if (resolvedCat == null) {
                    resolvedCat = categoryByName.get(catKey);
                }
                if (resolvedCat == null) {
                    try {
                        resolvedCat = categoryById.get(Long.parseLong(catKey));
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (resolvedCat == null) {
                String catDisplay = req.getCategory() != null ? req.getCategory() : (req.getCategoryId() != null ? String.valueOf(req.getCategoryId()) : "");
                errors.add("Không tìm thấy nhóm hàng: '" + catDisplay + "'");
            }

            // 4. Kiểm tra giá bán
            if (req.getPrice() != null && req.getPrice().compareTo(BigDecimal.ZERO) < 0) {
                errors.add("Giá bán không được âm");
            }
            validateImportMoney(req.getPrice(), "Giá bán", errors);

            // 5. Kiểm tra giá vốn
            if (req.getCostPrice() != null && req.getCostPrice().compareTo(BigDecimal.ZERO) < 0) {
                errors.add("Giá vốn không được âm");
            }
            validateImportMoney(req.getCostPrice(), "Giá vốn", errors);

            // 6. Kiểm tra độ dài các trường chuỗi
            if (req.getUnit() != null && req.getUnit().length() > 50) {
                errors.add("Đơn vị tính tối đa 50 ký tự");
            }
            if (req.getPackingSpecification() != null && req.getPackingSpecification().length() > 255) {
                errors.add("Quy cách đóng gói tối đa 255 ký tự");
            }
            if (req.getDescription() != null && req.getDescription().length() > 1000) {
                errors.add("Mô tả tối đa 1000 ký tự");
            }

            // 7. Xác định UPDATE (SKU đã tồn tại) hay CREATE (SKU mới)
            Product existing = !normalizedSku.isEmpty() ? existingProductMap.get(normalizedSku) : null;
            boolean isUpdate = (existing != null);
            String action = isUpdate ? "UPDATE" : "CREATE";
            Long existingId = isUpdate ? existing.getId() : null;

            boolean isValid = errors.isEmpty();
            if (isValid) {
                if (isUpdate) {
                    updateCount++;
                } else {
                    createCount++;
                }
            }

            BigDecimal displayCostPrice = canManageCostPrice ? req.getCostPrice() : null;

            rowResponses.add(ProductImportRowResponse.builder()
                    .rowNumber(rowNum)
                    .sku(normalizedSku)
                    .name(req.getName() != null ? req.getName().trim() : null)
                    .category(req.getCategory())
                    .resolvedCategoryId(resolvedCat != null ? resolvedCat.getId() : null)
                    .resolvedCategoryName(resolvedCat != null ? resolvedCat.getName() : null)
                    .unit(req.getUnit() != null && !req.getUnit().isBlank() ? req.getUnit().trim()
                            : existing != null ? existing.getUnit() : "cái")
                    .packingSpecification(req.getPackingSpecification() != null ? req.getPackingSpecification().trim() : null)
                    .price(req.getPrice() != null ? req.getPrice() : existing != null ? existing.getPrice() : BigDecimal.ZERO)
                    .costPrice(displayCostPrice)
                    .active(req.getActive() != null ? req.getActive() : existing != null ? existing.getActive() : true)
                    .description(req.getDescription() != null ? req.getDescription().trim() : null)
                    .action(action)
                    .isUpdate(isUpdate)
                    .existingProductId(existingId)
                    .valid(isValid)
                    .errors(errors)
                    .build());
        }

        int total = rowResponses.size();
        int valid = (int) rowResponses.stream().filter(ProductImportRowResponse::isValid).count();

        return ProductImportResponse.builder()
                .totalRows(total)
                .validRows(valid)
                .invalidRows(total - valid)
                .createCount(createCount)
                .updateCount(updateCount)
                .importedRows(0)
                .rows(rowResponses)
                .build();
    }

    @Override
    @Transactional
    public ProductImportResponse importProducts(MultipartFile file) {
        validateUploadFile(file);
        try {
            List<ProductImportRowRequest> requests = productExcelService.parseExcel(file.getInputStream());
            return importProducts(requests);
        } catch (IOException e) {
            throw new IllegalArgumentException("Không thể đọc nội dung tệp Excel: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public ProductImportResponse importProducts(List<ProductImportRowRequest> requests) {
        ProductImportResponse preview = previewImport(requests);
        List<ProductImportRowResponse> validRows = preview.getRows().stream()
                .filter(ProductImportRowResponse::isValid)
                .toList();

        if (validRows.isEmpty()) {
            return preview;
        }

        boolean canManageCostPrice = isSalesManager();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : "SYSTEM";

        // Preload danh mục
        Map<Long, Category> categoryMap = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        // Preload sản phẩm hiện có
        Set<String> skus = validRows.stream()
                .map(r -> r.getSku().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Map<String, Product> existingProductMap = fetchExistingProductsMap(skus);

        List<Product> productsToSave = new ArrayList<>();

        for (ProductImportRowResponse row : validRows) {
            if (row.isUpdate()) {
                Product existing = existingProductMap.get(row.getSku());
                if (existing != null) {
                    existing.setName(row.getName());
                    if (row.getResolvedCategoryId() != null && categoryMap.containsKey(row.getResolvedCategoryId())) {
                        existing.setCategory(categoryMap.get(row.getResolvedCategoryId()));
                    }
                    if (row.getUnit() != null && !row.getUnit().isBlank()) {
                        existing.setUnit(row.getUnit());
                    }
                    if (row.getPackingSpecification() != null) {
                        existing.setPackingSpecification(row.getPackingSpecification());
                    }
                    if (row.getPrice() != null) {
                        if (existing.getPrice() == null || existing.getPrice().compareTo(row.getPrice()) != 0) {
                            ProductPriceHistory history = ProductPriceHistory.builder()
                                    .product(existing)
                                    .oldPrice(existing.getPrice() != null ? existing.getPrice() : BigDecimal.ZERO)
                                    .newPrice(row.getPrice())
                                    .modifiedBy(username)
                                    .build();
                            productPriceHistoryRepository.save(history);

                            auditLogService.logAction(
                                    "PRICE",
                                    existing.getId().toString(),
                                    "UPDATE",
                                    existing.getPrice() != null ? existing.getPrice().toString() : "0",
                                    row.getPrice().toString(),
                                    username
                            );
                        }
                        existing.setPrice(row.getPrice());
                    }
                    // Bảo mật giá vốn: CHỈ Quản lý kinh doanh được sửa giá vốn
                    if (canManageCostPrice && row.getCostPrice() != null) {
                        existing.setCostPrice(row.getCostPrice());
                    }
                    if (row.getActive() != null) {
                        existing.setActive(row.getActive());
                    }
                    if (row.getDescription() != null) {
                        existing.setDescription(row.getDescription());
                    }
                    productsToSave.add(existing);
                }
            } else {
                Category cat = categoryMap.get(row.getResolvedCategoryId());
                BigDecimal cost = (canManageCostPrice && row.getCostPrice() != null) ? row.getCostPrice() : BigDecimal.ZERO;
                Product newProd = Product.builder()
                        .code(row.getSku())
                        .name(row.getName())
                        .category(cat)
                        .unit(row.getUnit() != null && !row.getUnit().isBlank() ? row.getUnit() : "cái")
                        .packingSpecification(row.getPackingSpecification())
                        .price(row.getPrice() != null ? row.getPrice() : BigDecimal.ZERO)
                        .costPrice(cost)
                        .revenue(BigDecimal.ZERO)
                        .hasTransactions(false)
                        .active(row.getActive() != null ? row.getActive() : true)
                        .description(row.getDescription())
                        .build();
                productsToSave.add(newProd);
            }
        }

        // Lưu hàng loạt (Batch save)
        List<Product> savedProducts = productRepository.saveAll(productsToSave);
        Map<String, ProductResponse> savedMap = mapToResponses(savedProducts).stream()
                .collect(Collectors.toMap(p -> p.getCode().toUpperCase(Locale.ROOT), p -> p));

        for (ProductImportRowResponse row : validRows) {
            ProductResponse saved = savedMap.get(row.getSku());
            if (saved != null) {
                row.setExistingProductId(saved.getId());
                row.setCreatedProduct(saved);
            }
        }

        auditLogService.logAction(
                "PRODUCT",
                "BATCH",
                "IMPORT",
                null,
                "Nhập hàng loạt " + savedProducts.size() + " sản phẩm từ Excel ("
                        + preview.getCreateCount() + " tạo mới, " + preview.getUpdateCount() + " cập nhật)",
                username
        );

        preview.setImportedRows(savedProducts.size());
        return preview;
    }

    private Map<String, Product> fetchExistingProductsMap(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Product> map = new HashMap<>();
        List<String> skuList = new ArrayList<>(skus);
        // Chia nhóm 500 SKU để đảm bảo an toàn truy vấn SQL khối lượng lớn (5.000+ sản phẩm)
        for (int i = 0; i < skuList.size(); i += 500) {
            List<String> chunk = skuList.subList(i, Math.min(i + 500, skuList.size()));
            List<Product> list = productRepository.findByCodeUpperIn(chunk);
            for (Product p : list) {
                if (p.getCode() != null) {
                    map.put(p.getCode().trim().toUpperCase(Locale.ROOT), p);
                }
            }
        }
        return map;
    }

    private void validateUploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Tập tin Excel không được để trống");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx") && !filename.toLowerCase(Locale.ROOT).endsWith(".xls"))) {
            throw new IllegalArgumentException("Định dạng tệp không hợp lệ. Vui lòng tải lên tệp Excel (.xlsx hoặc .xls)");
        }
        if (file.getSize() > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("Kích thước tệp vượt quá giới hạn tối đa 20MB");
        }
    }

    private void validateImportMoney(BigDecimal value, String label, List<String> errors) {
        if (value == null) return;
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() > 2) {
            errors.add(label + " chỉ được có tối đa 2 chữ số thập phân");
        }
        if (normalized.precision() - normalized.scale() > 13) {
            errors.add(label + " vượt quá giới hạn lưu trữ (13 chữ số phần nguyên)");
        }
    }

    /**
     * Kiểm tra xem người dùng hiện tại có vai trò được xem giá vốn hay không.
     * Quản trị viên luôn có toàn quyền.
     */
    private boolean isSalesManager() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) ||
                Permission.FINANCE_VIEW_COST_PRICE.name().equals(a.getAuthority())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductPriceHistoryResponse> getPriceHistory(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + productId));

        return productPriceHistoryRepository.findByProductIdOrderByAppliedAtDesc(productId)
                .stream()
                .map(h -> ProductPriceHistoryResponse.builder()
                        .id(h.getId())
                        .productId(product.getId())
                        .oldPrice(h.getOldPrice())
                        .newPrice(h.getNewPrice())
                        .modifiedBy(h.getModifiedBy())
                        .appliedAt(h.getAppliedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private ProductResponse mapToResponse(Product product) {
        BigDecimal revenue = productRepository.sumRevenueByProductId(product.getId());
        return mapToResponse(product, revenue, hasRecordedTransactions(product.getId()));
    }

    private ProductResponse mapToResponse(Product product, BigDecimal revenue, boolean hasTrans) {
        boolean canViewCostPrice = isSalesManager();
        java.util.List<com.quanlybanhang.dto.response.UnitConversionResponse> conversions = null;
        if (product.getUnitConversions() != null) {
            conversions = product.getUnitConversions().stream()
                    .map(uc -> com.quanlybanhang.dto.response.UnitConversionResponse.builder()
                            .id(uc.getId())
                            .productId(product.getId())
                            .productCode(product.getCode())
                            .productName(product.getName())
                            .unitName(uc.getUnitName())
                            .conversionFactor(uc.getConversionFactor())
                            .baseUnit(product.getUnit())
                            .isBaseUnit(uc.getIsBaseUnit() != null ? uc.getIsBaseUnit() : false)
                            .price(uc.getPrice())
                            .costPrice(canViewCostPrice ? uc.getCostPrice() : null)
                            .barcode(uc.getBarcode())
                            .description(uc.getDescription())
                            .createdAt(uc.getCreatedAt())
                            .updatedAt(uc.getUpdatedAt())
                            .build())
                    .collect(Collectors.toList());
        }
        return ProductResponse.builder()
                .id(product.getId())
                .code(product.getCode())
                .sku(product.getCode())
                .name(product.getName())
                .description(product.getDescription())
                .categoryId(product.getCategory() != null ? product.getCategory().getId() : null)
                .categoryCode(product.getCategory() != null ? product.getCategory().getCode() : null)
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .categoryLevel(product.getCategory() != null ? product.getCategory().getLevel() : null)
                .price(product.getPrice())
                .costPrice(canViewCostPrice ? product.getCostPrice() : null)
                .revenue(revenue != null ? revenue : BigDecimal.ZERO)
                .unit(product.getUnit())
                .packingSpecification(product.getPackingSpecification())
                .imageUrl(product.getImageUrl())
                .active(product.getActive())
                .manageLot(product.getManageLot() != null ? product.getManageLot() : false)
                .expiryWarningDays(product.getExpiryWarningDays() != null ? product.getExpiryWarningDays() : 30)
                .unitConversions(conversions)
                .status(Boolean.TRUE.equals(product.getActive()) ? "Đang kinh doanh" : "Ngừng kinh doanh")
                .hasTransactions(hasTrans)
                .canDelete(!hasTrans)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    private boolean hasRecordedTransactions(Long productId) {
        return productId != null && (orderItemRepository.existsByProductId(productId)
                || warehouseVoucherItemRepository.existsByProductId(productId));
    }
}
