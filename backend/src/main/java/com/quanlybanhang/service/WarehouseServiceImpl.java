package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.WarehouseVoucherItemRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.WarehouseInventoryResponse;
import com.quanlybanhang.dto.response.NamedResourceResponse;
import com.quanlybanhang.dto.response.WarehouseLotInventoryResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherItemResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WarehouseServiceImpl implements WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final UnitConversionRepository unitConversionRepository;
    private final UnitConversionService unitConversionService;
    private final WarehouseVoucherRepository voucherRepository;
    private final WarehouseInventoryRepository inventoryRepository;
    private final SupplierRepository supplierRepository;
    private final WarehouseLotInventoryRepository lotInventoryRepository;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(readOnly = true)
    public List<NamedResourceResponse> getWarehouseOptions() {
        return warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(warehouse -> new NamedResourceResponse(
                        warehouse.getId(), warehouse.getCode(), warehouse.getName()))
                .toList();
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse importGoods(WarehouseVoucherRequest request, String username) {
        return recordVoucher(request, WarehouseVoucherType.IMPORT, username);
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse exportGoods(WarehouseVoucherRequest request, String username) {
        return recordVoucher(request, WarehouseVoucherType.EXPORT, username);
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse adjustInventory(WarehouseVoucherRequest request, String username) {
        return recordVoucher(request, WarehouseVoucherType.ADJUSTMENT, username);
    }

    @Transactional
    public WarehouseVoucherResponse recordVoucher(WarehouseVoucherRequest request, WarehouseVoucherType type, String username) {
        if (request == null) {
            throw new IllegalArgumentException("Không được để trống nội dung phiếu kho");
        }

        // Chuẩn hóa danh sách items
        List<WarehouseVoucherItemRequest> itemRequests = normalizeItemRequests(request, type);
        if (itemRequests.isEmpty()) {
            throw new IllegalArgumentException("Phiếu kho phải có ít nhất 1 sản phẩm");
        }
        Warehouse warehouse = resolveWarehouse(request.getWarehouseId());

        String voucherCode = request.getVoucherCode();
        if (voucherCode == null || voucherCode.isBlank()) {
            String prefix = switch (type) {
                case IMPORT -> "PNK";
                case EXPORT -> "PXK";
                case ADJUSTMENT -> "PDC";
            };
            voucherCode = prefix + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        }

        // Kiểm tra nhà cung cấp nếu có cung cấp
        Supplier supplier = null;
        if (request.getSupplierId() != null || (request.getSupplierCode() != null && !request.getSupplierCode().isBlank())) {
            supplier = resolveSupplier(request.getSupplierId(), request.getSupplierCode());
            if (supplier != null && Boolean.FALSE.equals(supplier.getActive())) {
                throw new IllegalArgumentException("Nhà cung cấp '" + supplier.getName() + "' đã ngừng hoạt động");
            }
        }

        boolean isDraft = "DRAFT".equalsIgnoreCase(request.getStatus()) || Boolean.TRUE.equals(request.getDraft());
        String initialStatus = isDraft ? "DRAFT" : "RECORDED";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime voucherDate = request.getVoucherDate() != null ? request.getVoucherDate() : now;

        WarehouseVoucher voucher = WarehouseVoucher.builder()
                .voucherCode(voucherCode)
                .documentNumber(request.getDocumentNumber())
                .voucherDate(voucherDate)
                .type(type)
                .warehouse(warehouse)
                .supplier(supplier)
                .note(request.getNote())
                .status(initialStatus)
                .createdBy(username != null ? username : "system")
                .recordedAt(isDraft ? null : now)
                .referenceVoucherId(request.getReferenceVoucherId())
                .referenceVoucherCode(request.getReferenceVoucherCode())
                .adjustmentReason(request.getAdjustmentReason())
                .items(new ArrayList<>())
                .build();

        for (WarehouseVoucherItemRequest itemReq : itemRequests) {
            if (itemReq == null) {
                throw new IllegalArgumentException("Dòng sản phẩm trong phiếu kho không được để trống");
            }
            Product product = resolveProduct(itemReq);
            if (!Boolean.TRUE.equals(product.getActive())) {
                throw new IllegalArgumentException("Sản phẩm '" + product.getName() + "' đã ngừng kinh doanh");
            }

            String inputUnit = itemReq.getUnit();
            if (inputUnit == null || inputUnit.isBlank()) {
                inputUnit = product.getUnit();
            } else {
                inputUnit = inputUnit.trim();
            }

            BigDecimal qty = itemReq.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng cho sản phẩm '" + product.getCode() + "' phải lớn hơn 0");
            }

            // Lấy hệ số quy đổi tại thời điểm lập phiếu (S2-07)
            BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);

            // Quy đổi về đơn vị cơ sở: baseQuantity = quantity * factor
            BigDecimal baseQty = qty.multiply(factor);

            // Quản lý số lô và hạn sử dụng (S5-04)
            boolean isLotManaged = Boolean.TRUE.equals(product.getManageLot());
            String lotNumber = itemReq.getLotNumber() != null ? itemReq.getLotNumber().trim() : null;
            LocalDate expiryDate = itemReq.getExpiryDate();
            LocalDate mfgDate = itemReq.getManufacturingDate();

            if (isLotManaged) {
                if (lotNumber == null || lotNumber.isBlank()) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập số lô");
                }
                if (expiryDate == null) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập hạn sử dụng");
                }
                if (mfgDate != null && expiryDate.isBefore(mfgDate)) {
                    throw new IllegalArgumentException("Hạn sử dụng không được trước ngày sản xuất cho lô '" + lotNumber + "'");
                }
            }

            BigDecimal price = itemReq.getPrice() != null ? itemReq.getPrice() : product.getPrice();
            BigDecimal totalAmount = price != null ? price.multiply(qty) : BigDecimal.ZERO;

            // ĐÓNG BĂNG thông tin đơn vị, hệ số và số lượng cơ sở vào dòng phiếu
            WarehouseVoucherItem item = WarehouseVoucherItem.builder()
                    .voucher(voucher)
                    .product(product)
                    .unit(inputUnit)
                    .quantity(qty)
                    .conversionFactor(factor)
                    .baseUnit(product.getUnit())
                    .baseQuantity(baseQty)
                    .price(price)
                    .totalAmount(totalAmount)
                    .lotNumber(lotNumber)
                    .expiryDate(expiryDate)
                    .manufacturingDate(mfgDate)
                    .note(itemReq.getNote())
                    .build();

            voucher.addItem(item);

            // CHỈ CỘNG TỒN KHO KHI XÁC NHẬN / GHI SỔ, PHIẾU NHÁP KHÔNG ẢNH HƯỞNG TỒN (S5-04)
            if (!isDraft) {
                WarehouseInventory inventory = inventoryRepository
                        .findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                        .orElseGet(() -> WarehouseInventory.builder()
                                .warehouse(warehouse)
                                .product(product)
                                .baseQuantity(BigDecimal.ZERO)
                                .baseUnit(product.getUnit())
                                .build());
                BigDecimal quantityBefore = inventory.getBaseQuantity() == null
                        ? BigDecimal.ZERO : inventory.getBaseQuantity();

                if (type == WarehouseVoucherType.IMPORT) {
                    inventory.addBaseQuantity(baseQty);
                } else if (type == WarehouseVoucherType.EXPORT) {
                    if (inventory.getBaseQuantity().compareTo(baseQty) < 0) {
                        throw new IllegalArgumentException("Không đủ tồn kho để xuất cho sản phẩm '" + product.getName() +
                                "'. Tồn hiện tại: " + inventory.getBaseQuantity() + " " + product.getUnit() +
                                ", yêu cầu xuất: " + baseQty + " " + product.getUnit() + " (" + qty + " " + inputUnit + ")");
                    }
                    inventory.subtractBaseQuantity(baseQty);
                } else if (type == WarehouseVoucherType.ADJUSTMENT) {
                    if (request.getDelta() != null) {
                        BigDecimal deltaBase = request.getDelta().multiply(factor);
                        inventory.addBaseQuantity(deltaBase);
                    } else {
                        inventory.setBaseQuantity(baseQty);
                    }
                }

                inventoryRepository.save(inventory);

                // Cập nhật tồn kho theo lô nếu có lotNumber
                if (lotNumber != null && !lotNumber.isBlank()) {
                    WarehouseLotInventory lotInv = lotInventoryRepository
                            .findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), lotNumber)
                            .orElseGet(() -> WarehouseLotInventory.builder()
                                    .warehouse(warehouse)
                                    .product(product)
                                    .lotNumber(lotNumber)
                                    .expiryDate(expiryDate)
                                    .manufacturingDate(mfgDate)
                                    .baseQuantity(BigDecimal.ZERO)
                                    .baseUnit(product.getUnit())
                                    .build());
                    if (type == WarehouseVoucherType.IMPORT) {
                        lotInv.addBaseQuantity(baseQty);
                    } else if (type == WarehouseVoucherType.EXPORT) {
                        lotInv.subtractBaseQuantity(baseQty);
                    }
                    lotInventoryRepository.save(lotInv);
                }

                auditLogService.logAction(
                        "INVENTORY",
                        warehouse.getCode() + ":" + product.getCode(),
                        type.name(),
                        quantityBefore.toPlainString() + " " + product.getUnit(),
                        inventory.getBaseQuantity().toPlainString() + " " + product.getUnit(),
                        username != null ? username : "system");
            }
        }

        WarehouseVoucher savedVoucher = voucherRepository.save(voucher);
        return mapToVoucherResponse(savedVoucher);
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse confirmVoucher(Long id, String username) {
        WarehouseVoucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho với ID: " + id));

        if (!"DRAFT".equalsIgnoreCase(voucher.getStatus())) {
            throw new IllegalStateException("Chỉ có thể xác nhận phiếu ở trạng thái Nháp (DRAFT). Trạng thái hiện tại: " + voucher.getStatus());
        }

        Warehouse warehouse = voucher.getWarehouse();
        if (warehouse == null || !Boolean.TRUE.equals(warehouse.getActive())) {
            throw new IllegalArgumentException("Kho của phiếu nhập đã ngừng hoạt động hoặc không tồn tại");
        }

        if (voucher.getItems() == null || voucher.getItems().isEmpty()) {
            throw new IllegalArgumentException("Phiếu kho không có sản phẩm để xác nhận");
        }

        for (WarehouseVoucherItem item : voucher.getItems()) {
            Product product = item.getProduct();
            if (product == null || !Boolean.TRUE.equals(product.getActive())) {
                throw new IllegalArgumentException("Sản phẩm '" + (product != null ? product.getName() : "") + "' đã ngừng kinh doanh");
            }

            // Kiểm tra ràng buộc lô
            if (Boolean.TRUE.equals(product.getManageLot())) {
                if (item.getLotNumber() == null || item.getLotNumber().isBlank()) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập số lô");
                }
                if (item.getExpiryDate() == null) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập hạn sử dụng");
                }
            }

            BigDecimal baseQty = item.getBaseQuantity();

            // Cập nhật sổ sách tồn kho tổng
            WarehouseInventory inventory = inventoryRepository
                    .findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                    .orElseGet(() -> WarehouseInventory.builder()
                            .warehouse(warehouse)
                            .product(product)
                            .baseQuantity(BigDecimal.ZERO)
                            .baseUnit(product.getUnit())
                            .build());

            BigDecimal quantityBefore = inventory.getBaseQuantity() == null ? BigDecimal.ZERO : inventory.getBaseQuantity();

            if (voucher.getType() == WarehouseVoucherType.IMPORT) {
                inventory.addBaseQuantity(baseQty);
            } else if (voucher.getType() == WarehouseVoucherType.EXPORT) {
                if (inventory.getBaseQuantity().compareTo(baseQty) < 0) {
                    throw new IllegalArgumentException("Không đủ tồn kho để xuất cho sản phẩm '" + product.getName() +
                            "'. Tồn hiện tại: " + inventory.getBaseQuantity() + " " + product.getUnit() +
                            ", yêu cầu: " + baseQty + " " + product.getUnit());
                }
                inventory.subtractBaseQuantity(baseQty);
            }

            inventoryRepository.save(inventory);

            // Cập nhật tồn kho theo lô
            if (item.getLotNumber() != null && !item.getLotNumber().isBlank()) {
                WarehouseLotInventory lotInv = lotInventoryRepository
                        .findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), item.getLotNumber())
                        .orElseGet(() -> WarehouseLotInventory.builder()
                                .warehouse(warehouse)
                                .product(product)
                                .lotNumber(item.getLotNumber())
                                .expiryDate(item.getExpiryDate())
                                .manufacturingDate(item.getManufacturingDate())
                                .baseQuantity(BigDecimal.ZERO)
                                .baseUnit(product.getUnit())
                                .build());
                if (voucher.getType() == WarehouseVoucherType.IMPORT) {
                    lotInv.addBaseQuantity(baseQty);
                } else if (voucher.getType() == WarehouseVoucherType.EXPORT) {
                    lotInv.subtractBaseQuantity(baseQty);
                }
                lotInventoryRepository.save(lotInv);
            }

            auditLogService.logAction(
                    "INVENTORY",
                    warehouse.getCode() + ":" + product.getCode(),
                    voucher.getType().name() + "_CONFIRM",
                    quantityBefore.toPlainString() + " " + product.getUnit(),
                    inventory.getBaseQuantity().toPlainString() + " " + product.getUnit(),
                    username != null ? username : "system");
        }

        voucher.setStatus("CONFIRMED");
        voucher.setRecordedAt(LocalDateTime.now());
        WarehouseVoucher saved = voucherRepository.save(voucher);
        return mapToVoucherResponse(saved);
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse updateVoucher(Long id, WarehouseVoucherRequest request, String username) {
        WarehouseVoucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho với ID: " + id));

        // Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh (S5-04)
        if ("CONFIRMED".equalsIgnoreCase(voucher.getStatus()) || "RECORDED".equalsIgnoreCase(voucher.getStatus()) || "ADJUSTED".equalsIgnoreCase(voucher.getStatus())) {
            throw new IllegalStateException("Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh");
        }
        if (!"DRAFT".equalsIgnoreCase(voucher.getStatus())) {
            throw new IllegalStateException("Chỉ được sửa phiếu ở trạng thái Nháp (DRAFT). Trạng thái hiện tại: " + voucher.getStatus());
        }

        if (request.getWarehouseId() != null) {
            voucher.setWarehouse(resolveWarehouse(request.getWarehouseId()));
        }

        if (request.getSupplierId() != null || (request.getSupplierCode() != null && !request.getSupplierCode().isBlank())) {
            Supplier supplier = resolveSupplier(request.getSupplierId(), request.getSupplierCode());
            if (supplier != null && Boolean.FALSE.equals(supplier.getActive())) {
                throw new IllegalArgumentException("Nhà cung cấp '" + supplier.getName() + "' đã ngừng hoạt động");
            }
            voucher.setSupplier(supplier);
        }

        if (request.getDocumentNumber() != null) {
            voucher.setDocumentNumber(request.getDocumentNumber());
        }
        if (request.getVoucherDate() != null) {
            voucher.setVoucherDate(request.getVoucherDate());
        }
        if (request.getNote() != null) {
            voucher.setNote(request.getNote());
        }

        // Cập nhật danh sách items nếu có truyền
        List<WarehouseVoucherItemRequest> itemRequests = normalizeItemRequests(request, voucher.getType());
        if (!itemRequests.isEmpty()) {
            voucher.getItems().clear();
            for (WarehouseVoucherItemRequest itemReq : itemRequests) {
                Product product = resolveProduct(itemReq);
                if (!Boolean.TRUE.equals(product.getActive())) {
                    throw new IllegalArgumentException("Sản phẩm '" + product.getName() + "' đã ngừng kinh doanh");
                }

                String inputUnit = itemReq.getUnit();
                if (inputUnit == null || inputUnit.isBlank()) {
                    inputUnit = product.getUnit();
                } else {
                    inputUnit = inputUnit.trim();
                }

                BigDecimal qty = itemReq.getQuantity();
                if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Số lượng cho sản phẩm '" + product.getCode() + "' phải lớn hơn 0");
                }

                BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);
                BigDecimal baseQty = qty.multiply(factor);

                boolean isLotManaged = Boolean.TRUE.equals(product.getManageLot());
                String lotNumber = itemReq.getLotNumber() != null ? itemReq.getLotNumber().trim() : null;
                LocalDate expiryDate = itemReq.getExpiryDate();
                LocalDate mfgDate = itemReq.getManufacturingDate();

                if (isLotManaged) {
                    if (lotNumber == null || lotNumber.isBlank()) {
                        throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập số lô");
                    }
                    if (expiryDate == null) {
                        throw new IllegalArgumentException("Sản phẩm '" + product.getCode() + " - " + product.getName() + "' có quản lý lô, bắt buộc phải nhập hạn sử dụng");
                    }
                    if (mfgDate != null && expiryDate.isBefore(mfgDate)) {
                        throw new IllegalArgumentException("Hạn sử dụng không được trước ngày sản xuất cho lô '" + lotNumber + "'");
                    }
                }

                BigDecimal price = itemReq.getPrice() != null ? itemReq.getPrice() : product.getPrice();
                BigDecimal totalAmount = price != null ? price.multiply(qty) : BigDecimal.ZERO;

                WarehouseVoucherItem item = WarehouseVoucherItem.builder()
                        .voucher(voucher)
                        .product(product)
                        .unit(inputUnit)
                        .quantity(qty)
                        .conversionFactor(factor)
                        .baseUnit(product.getUnit())
                        .baseQuantity(baseQty)
                        .price(price)
                        .totalAmount(totalAmount)
                        .lotNumber(lotNumber)
                        .expiryDate(expiryDate)
                        .manufacturingDate(mfgDate)
                        .note(itemReq.getNote())
                        .build();

                voucher.addItem(item);
            }
        }

        WarehouseVoucher saved = voucherRepository.save(voucher);
        return mapToVoucherResponse(saved);
    }

    @Override
    @Transactional
    public void deleteVoucher(Long id, String username) {
        WarehouseVoucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho với ID: " + id));

        // Phiếu đã xác nhận không thể xóa, chỉ lập phiếu điều chỉnh (S5-04)
        if ("CONFIRMED".equalsIgnoreCase(voucher.getStatus()) || "RECORDED".equalsIgnoreCase(voucher.getStatus()) || "ADJUSTED".equalsIgnoreCase(voucher.getStatus())) {
            throw new IllegalStateException("Phiếu đã xác nhận không thể xóa, chỉ lập phiếu điều chỉnh");
        }

        voucherRepository.delete(voucher);
        auditLogService.logAction(
                "WAREHOUSE_VOUCHER",
                voucher.getVoucherCode(),
                "DELETE_DRAFT",
                voucher.getStatus(),
                "DELETED",
                username != null ? username : "system");
    }

    @Override
    @Transactional
    public WarehouseVoucherResponse adjustVoucher(Long id, WarehouseVoucherRequest request, String username) {
        WarehouseVoucher originalVoucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho gốc với ID: " + id));

        if (!"CONFIRMED".equalsIgnoreCase(originalVoucher.getStatus()) && !"RECORDED".equalsIgnoreCase(originalVoucher.getStatus()) && !"ADJUSTED".equalsIgnoreCase(originalVoucher.getStatus())) {
            throw new IllegalStateException("Chỉ có thể lập phiếu điều chỉnh cho phiếu kho đã xác nhận. Trạng thái hiện tại: " + originalVoucher.getStatus());
        }

        if (request == null) {
            throw new IllegalArgumentException("Không được để trống nội dung phiếu điều chỉnh");
        }

        Warehouse warehouse = request.getWarehouseId() != null
                ? resolveWarehouse(request.getWarehouseId())
                : originalVoucher.getWarehouse();

        String voucherCode = request.getVoucherCode();
        if (voucherCode == null || voucherCode.isBlank()) {
            voucherCode = "PDC-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        }

        String reason = request.getAdjustmentReason() != null && !request.getAdjustmentReason().isBlank()
                ? request.getAdjustmentReason()
                : (request.getNote() != null ? request.getNote() : "Điều chỉnh cho phiếu " + originalVoucher.getVoucherCode());

        WarehouseVoucher adjustmentVoucher = WarehouseVoucher.builder()
                .voucherCode(voucherCode)
                .documentNumber(request.getDocumentNumber())
                .type(WarehouseVoucherType.ADJUSTMENT)
                .warehouse(warehouse)
                .supplier(originalVoucher.getSupplier())
                .referenceVoucherId(originalVoucher.getId())
                .referenceVoucherCode(originalVoucher.getVoucherCode())
                .adjustmentReason(reason)
                .note(request.getNote())
                .status("CONFIRMED")
                .createdBy(username != null ? username : "system")
                .voucherDate(request.getVoucherDate() != null ? request.getVoucherDate() : LocalDateTime.now())
                .recordedAt(LocalDateTime.now())
                .items(new ArrayList<>())
                .build();

        List<WarehouseVoucherItemRequest> itemRequests = normalizeItemRequests(request, WarehouseVoucherType.ADJUSTMENT);
        if (itemRequests.isEmpty()) {
            throw new IllegalArgumentException("Phiếu điều chỉnh phải có ít nhất 1 sản phẩm");
        }

        for (WarehouseVoucherItemRequest itemReq : itemRequests) {
            Product product = resolveProduct(itemReq);

            String inputUnit = itemReq.getUnit();
            if (inputUnit == null || inputUnit.isBlank()) {
                inputUnit = product.getUnit();
            } else {
                inputUnit = inputUnit.trim();
            }

            BigDecimal qty = itemReq.getQuantity() != null ? itemReq.getQuantity() : BigDecimal.ZERO;
            BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);
            BigDecimal baseQty = qty.multiply(factor);

            String lotNumber = itemReq.getLotNumber() != null ? itemReq.getLotNumber().trim() : null;
            LocalDate expiryDate = itemReq.getExpiryDate();
            LocalDate mfgDate = itemReq.getManufacturingDate();

            BigDecimal price = itemReq.getPrice() != null ? itemReq.getPrice() : product.getPrice();
            BigDecimal totalAmount = price != null ? price.multiply(qty) : BigDecimal.ZERO;

            WarehouseVoucherItem item = WarehouseVoucherItem.builder()
                    .voucher(adjustmentVoucher)
                    .product(product)
                    .unit(inputUnit)
                    .quantity(qty)
                    .conversionFactor(factor)
                    .baseUnit(product.getUnit())
                    .baseQuantity(baseQty)
                    .price(price)
                    .totalAmount(totalAmount)
                    .lotNumber(lotNumber)
                    .expiryDate(expiryDate)
                    .manufacturingDate(mfgDate)
                    .note(itemReq.getNote())
                    .build();

            adjustmentVoucher.addItem(item);

            // Cập nhật tồn kho theo delta hoặc số lượng điều chỉnh
            WarehouseInventory inventory = inventoryRepository
                    .findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                    .orElseGet(() -> WarehouseInventory.builder()
                            .warehouse(warehouse)
                            .product(product)
                            .baseQuantity(BigDecimal.ZERO)
                            .baseUnit(product.getUnit())
                            .build());

            BigDecimal quantityBefore = inventory.getBaseQuantity() == null ? BigDecimal.ZERO : inventory.getBaseQuantity();

            if (request.getDelta() != null) {
                BigDecimal deltaBase = request.getDelta().multiply(factor);
                inventory.addBaseQuantity(deltaBase);
            } else {
                inventory.setBaseQuantity(baseQty);
            }

            inventoryRepository.save(inventory);

            // Cập nhật tồn kho lô nếu có
            if (lotNumber != null && !lotNumber.isBlank()) {
                WarehouseLotInventory lotInv = lotInventoryRepository
                        .findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), lotNumber)
                        .orElseGet(() -> WarehouseLotInventory.builder()
                                .warehouse(warehouse)
                                .product(product)
                                .lotNumber(lotNumber)
                                .expiryDate(expiryDate)
                                .manufacturingDate(mfgDate)
                                .baseQuantity(BigDecimal.ZERO)
                                .baseUnit(product.getUnit())
                                .build());
                if (request.getDelta() != null) {
                    BigDecimal deltaBase = request.getDelta().multiply(factor);
                    lotInv.addBaseQuantity(deltaBase);
                } else {
                    lotInv.setBaseQuantity(baseQty);
                }
                lotInventoryRepository.save(lotInv);
            }

            auditLogService.logAction(
                    "INVENTORY",
                    warehouse.getCode() + ":" + product.getCode(),
                    "ADJUSTMENT",
                    quantityBefore.toPlainString() + " " + product.getUnit(),
                    inventory.getBaseQuantity().toPlainString() + " " + product.getUnit(),
                    username != null ? username : "system");
        }

        originalVoucher.setStatus("ADJUSTED");
        voucherRepository.save(originalVoucher);

        WarehouseVoucher saved = voucherRepository.save(adjustmentVoucher);
        return mapToVoucherResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseLotInventoryResponse> getLotInventory(Long warehouseId, Long productId) {
        List<WarehouseLotInventory> list;
        if (warehouseId != null && productId != null) {
            list = lotInventoryRepository.findByWarehouseIdAndProductId(warehouseId, productId);
        } else if (warehouseId != null) {
            list = lotInventoryRepository.findByWarehouseId(warehouseId);
        } else if (productId != null) {
            list = lotInventoryRepository.findByProductId(productId);
        } else {
            list = lotInventoryRepository.findAllByOrderByUpdatedAtDesc();
        }

        return list.stream().map(lot -> WarehouseLotInventoryResponse.builder()
                .id(lot.getId())
                .warehouseId(lot.getWarehouse() != null ? lot.getWarehouse().getId() : null)
                .warehouseCode(lot.getWarehouse() != null ? lot.getWarehouse().getCode() : null)
                .warehouseName(lot.getWarehouse() != null ? lot.getWarehouse().getName() : null)
                .productId(lot.getProduct() != null ? lot.getProduct().getId() : null)
                .productCode(lot.getProduct() != null ? lot.getProduct().getCode() : null)
                .productName(lot.getProduct() != null ? lot.getProduct().getName() : null)
                .lotNumber(lot.getLotNumber())
                .expiryDate(lot.getExpiryDate())
                .manufacturingDate(lot.getManufacturingDate())
                .baseQuantity(lot.getBaseQuantity())
                .baseUnit(lot.getBaseUnit())
                .updatedAt(lot.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseInventoryResponse> getInventory(Long warehouseId) {
        List<WarehouseInventory> list;
        if (warehouseId != null) {
            list = inventoryRepository.findByWarehouseId(warehouseId);
        } else {
            list = inventoryRepository.findAll();
        }

        return list.stream().map(this::mapToInventoryResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseVoucherResponse> getVouchers(Long warehouseId, WarehouseVoucherType type) {
        return getVouchers(warehouseId, type, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseVoucherResponse> getVouchers(Long warehouseId, WarehouseVoucherType type, String status, Long supplierId) {
        List<WarehouseVoucher> vouchers = voucherRepository.searchVouchers(warehouseId, type, status, supplierId);
        return vouchers.stream().map(this::mapToVoucherResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseVoucherResponse getVoucherById(Long id) {
        WarehouseVoucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu kho với ID: " + id));
        return mapToVoucherResponse(voucher);
    }

    private Warehouse resolveWarehouse(Long warehouseId) {
        if (warehouseId != null) {
            Warehouse warehouse = warehouseRepository.findById(warehouseId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + warehouseId));
            if (!Boolean.TRUE.equals(warehouse.getActive())) {
                throw new IllegalArgumentException("Kho đã ngừng hoạt động");
            }
            return warehouse;
        }
        List<Warehouse> warehouses = warehouseRepository.findAllByActiveTrueOrderByNameAsc();
        if (warehouses.isEmpty()) {
            throw new IllegalArgumentException("Chưa có kho đang hoạt động. Vui lòng cấu hình kho thực tế trước khi ghi phiếu.");
        }
        if (warehouses.size() > 1) {
            throw new IllegalArgumentException("Vui lòng chọn kho (warehouseId) để ghi phiếu");
        }
        return warehouses.get(0);
    }

    private Supplier resolveSupplier(Long supplierId, String supplierCode) {
        if (supplierId != null) {
            return supplierRepository.findById(supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp với ID: " + supplierId));
        }
        if (supplierCode != null && !supplierCode.isBlank()) {
            return supplierRepository.findByCode(supplierCode.trim())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà cung cấp với mã: " + supplierCode));
        }
        return null;
    }

    private Product resolveProduct(WarehouseVoucherItemRequest itemReq) {
        if (itemReq.getProductId() != null) {
            return productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + itemReq.getProductId()));
        }
        if (itemReq.getProductCode() != null && !itemReq.getProductCode().isBlank()) {
            return productRepository.findByCode(itemReq.getProductCode().trim().toUpperCase())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với mã: " + itemReq.getProductCode()));
        }
        throw new IllegalArgumentException("Cần cung cấp productId hoặc productCode");
    }

    private List<WarehouseVoucherItemRequest> normalizeItemRequests(WarehouseVoucherRequest request, WarehouseVoucherType type) {
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            return request.getItems();
        }
        // Trường hợp payload đơn giản {"productId": 1, "quantity": 50, "unit": "thùng"}
        if (request.getProductId() != null) {
            BigDecimal qty = request.getQuantity();
            if (qty == null && type == WarehouseVoucherType.ADJUSTMENT && request.getDelta() != null) {
                qty = request.getDelta().abs();
            }
            if (qty == null) {
                throw new IllegalArgumentException("Số lượng không được để trống");
            }
            return List.of(WarehouseVoucherItemRequest.builder()
                    .productId(request.getProductId())
                    .quantity(qty)
                    .unit(request.getUnit())
                    .build());
        }
        return Collections.emptyList();
    }

    private WarehouseVoucherResponse mapToVoucherResponse(WarehouseVoucher voucher) {
        List<WarehouseVoucherItemResponse> items = voucher.getItems() != null
                ? voucher.getItems().stream().map(this::mapToVoucherItemResponse).collect(Collectors.toList())
                : Collections.emptyList();

        return WarehouseVoucherResponse.builder()
                .id(voucher.getId())
                .voucherCode(voucher.getVoucherCode())
                .documentNumber(voucher.getDocumentNumber())
                .voucherDate(voucher.getVoucherDate())
                .type(voucher.getType())
                .warehouseId(voucher.getWarehouse() != null ? voucher.getWarehouse().getId() : null)
                .warehouseCode(voucher.getWarehouse() != null ? voucher.getWarehouse().getCode() : null)
                .warehouseName(voucher.getWarehouse() != null ? voucher.getWarehouse().getName() : null)
                .supplierId(voucher.getSupplier() != null ? voucher.getSupplier().getId() : null)
                .supplierCode(voucher.getSupplier() != null ? voucher.getSupplier().getCode() : null)
                .supplierName(voucher.getSupplier() != null ? voucher.getSupplier().getName() : null)
                .status(voucher.getStatus())
                .referenceVoucherId(voucher.getReferenceVoucherId())
                .referenceVoucherCode(voucher.getReferenceVoucherCode())
                .adjustmentReason(voucher.getAdjustmentReason())
                .note(voucher.getNote())
                .createdBy(voucher.getCreatedBy())
                .recordedAt(voucher.getRecordedAt())
                .items(items)
                .build();
    }

    private WarehouseVoucherItemResponse mapToVoucherItemResponse(WarehouseVoucherItem item) {
        return WarehouseVoucherItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                .productCode(item.getProduct() != null ? item.getProduct().getCode() : null)
                .productName(item.getProduct() != null ? item.getProduct().getName() : null)
                .manageLot(item.getProduct() != null ? Boolean.TRUE.equals(item.getProduct().getManageLot()) : false)
                .unit(item.getUnit())
                .quantity(item.getQuantity())
                .conversionFactor(item.getConversionFactor())
                .baseUnit(item.getBaseUnit())
                .baseQuantity(item.getBaseQuantity())
                .price(item.getPrice())
                .totalAmount(item.getTotalAmount())
                .lotNumber(item.getLotNumber())
                .expiryDate(item.getExpiryDate())
                .manufacturingDate(item.getManufacturingDate())
                .note(item.getNote())
                .build();
    }

    private WarehouseInventoryResponse mapToInventoryResponse(WarehouseInventory inv) {
        Product p = inv.getProduct();
        Map<String, BigDecimal> converted = new LinkedHashMap<>();

        // Luôn có đơn vị cơ sở
        converted.put(inv.getBaseUnit(), inv.getBaseQuantity());

        // Tính tương đương theo các đơn vị quy đổi đã khai báo của SKU
        if (p != null) {
            List<UnitConversion> conversions = unitConversionRepository.findByProductIdOrderByConversionFactorAsc(p.getId());
            for (UnitConversion uc : conversions) {
                if (uc.getConversionFactor() != null && uc.getConversionFactor().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal qtyInUnit = inv.getBaseQuantity().divide(uc.getConversionFactor(), 2, RoundingMode.HALF_UP);
                    converted.put(uc.getUnitName(), qtyInUnit);
                }
            }
        }

        return WarehouseInventoryResponse.builder()
                .id(inv.getId())
                .warehouseId(inv.getWarehouse() != null ? inv.getWarehouse().getId() : null)
                .warehouseCode(inv.getWarehouse() != null ? inv.getWarehouse().getCode() : null)
                .warehouseName(inv.getWarehouse() != null ? inv.getWarehouse().getName() : null)
                .productId(p != null ? p.getId() : null)
                .productCode(p != null ? p.getCode() : null)
                .productName(p != null ? p.getName() : null)
                .baseQuantity(inv.getBaseQuantity())
                .baseUnit(inv.getBaseUnit())
                .convertedViews(converted)
                .updatedAt(inv.getUpdatedAt())
                .build();
    }
}
