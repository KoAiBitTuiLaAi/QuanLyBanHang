package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.WarehouseVoucherItemRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.WarehouseInventoryResponse;
import com.quanlybanhang.dto.response.NamedResourceResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherItemResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

        WarehouseVoucher voucher = WarehouseVoucher.builder()
                .voucherCode(voucherCode)
                .type(type)
                .warehouse(warehouse)
                .note(request.getNote())
                .status("RECORDED")
                .createdBy(username != null ? username : "system")
                .recordedAt(LocalDateTime.now())
                .items(new ArrayList<>())
                .build();

        for (WarehouseVoucherItemRequest itemReq : itemRequests) {
            if (itemReq == null) {
                throw new IllegalArgumentException("Dòng sản phẩm trong phiếu kho không được để trống");
            }
            Product product = resolveProduct(itemReq);

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

            // Lấy hệ số quy đổi tại thời điểm ghi sổ (S2-07)
            BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);

            // Quy đổi về đơn vị cơ sở: baseQuantity = quantity * factor
            BigDecimal baseQty = qty.multiply(factor);

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
                    .note(itemReq.getNote())
                    .build();

            voucher.addItem(item);

            // Ghi sổ vào tồn kho theo đơn vị cơ sở (số lon)
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
            auditLogService.logAction(
                    "INVENTORY",
                    warehouse.getCode() + ":" + product.getCode(),
                    type.name(),
                    quantityBefore.toPlainString() + " " + product.getUnit(),
                    inventory.getBaseQuantity().toPlainString() + " " + product.getUnit(),
                    username != null ? username : "system");
        }

        WarehouseVoucher savedVoucher = voucherRepository.save(voucher);
        return mapToVoucherResponse(savedVoucher);
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
        List<WarehouseVoucher> vouchers;
        if (type != null) {
            vouchers = voucherRepository.findByTypeOrderByRecordedAtDesc(type);
        } else if (warehouseId != null) {
            vouchers = voucherRepository.findByWarehouseIdOrderByRecordedAtDesc(warehouseId);
        } else {
            vouchers = voucherRepository.findAllByOrderByRecordedAtDesc();
        }
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
                .type(voucher.getType())
                .warehouseId(voucher.getWarehouse() != null ? voucher.getWarehouse().getId() : null)
                .warehouseCode(voucher.getWarehouse() != null ? voucher.getWarehouse().getCode() : null)
                .warehouseName(voucher.getWarehouse() != null ? voucher.getWarehouse().getName() : null)
                .status(voucher.getStatus())
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
                .unit(item.getUnit())
                .quantity(item.getQuantity())
                .conversionFactor(item.getConversionFactor())
                .baseUnit(item.getBaseUnit())
                .baseQuantity(item.getBaseQuantity())
                .price(item.getPrice())
                .totalAmount(item.getTotalAmount())
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
                .reservedQuantity(inv.getReservedQuantity() != null ? inv.getReservedQuantity() : BigDecimal.ZERO)
                .availableQuantity(inv.getAvailableQuantity())
                .baseUnit(inv.getBaseUnit())
                .convertedViews(converted)
                .updatedAt(inv.getUpdatedAt())
                .build();
    }
}
