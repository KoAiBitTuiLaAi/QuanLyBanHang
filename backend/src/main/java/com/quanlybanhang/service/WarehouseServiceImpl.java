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
import com.quanlybanhang.dto.request.WarehouseTransferItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferReceiveItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferReceiveRequest;
import com.quanlybanhang.dto.request.WarehouseTransferRequest;
import com.quanlybanhang.dto.response.WarehouseTransferItemResponse;
import com.quanlybanhang.dto.response.WarehouseTransferResponse;
import com.quanlybanhang.model.WarehouseTransfer;
import com.quanlybanhang.model.WarehouseTransferItem;
import com.quanlybanhang.model.WarehouseTransferStatus;
import com.quanlybanhang.repository.WarehouseTransferRepository;
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
    private final WarehouseTransferRepository warehouseTransferRepository;
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

    private Product resolveProduct(Long productId, String productCode) {
        if (productId != null) {
            return productRepository.findById(productId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + productId));
        }
        if (productCode != null && !productCode.isBlank()) {
            return productRepository.findByCode(productCode.trim().toUpperCase())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với mã: " + productCode));
        }
        throw new IllegalArgumentException("Cần cung cấp productId hoặc productCode");
    }

    private Product resolveProduct(WarehouseVoucherItemRequest itemReq) {
        return resolveProduct(itemReq.getProductId(), itemReq.getProductCode());
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
                .inTransitQuantity(inv.getInTransitQuantity() != null ? inv.getInTransitQuantity() : BigDecimal.ZERO)
                .baseUnit(inv.getBaseUnit())
                .convertedViews(converted)
                .updatedAt(inv.getUpdatedAt())
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CHUYỂN KHO NỘI BỘ (S5-07)
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public WarehouseTransferResponse createTransfer(WarehouseTransferRequest request, String username) {
        if (request == null) {
            throw new IllegalArgumentException("Không được để trống nội dung phiếu chuyển kho");
        }
        if (request.getFromWarehouseId() == null) {
            throw new IllegalArgumentException("Kho xuất (kho đi) không được để trống");
        }
        if (request.getToWarehouseId() == null) {
            throw new IllegalArgumentException("Kho nhập (kho đến) không được để trống");
        }
        if (Objects.equals(request.getFromWarehouseId(), request.getToWarehouseId())) {
            throw new IllegalArgumentException("Kho xuất và kho nhập không được trùng nhau");
        }

        Warehouse fromWarehouse = warehouseRepository.findById(request.getFromWarehouseId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho xuất với ID: " + request.getFromWarehouseId()));
        if (!Boolean.TRUE.equals(fromWarehouse.getActive())) {
            throw new IllegalArgumentException("Kho xuất đã ngừng hoạt động");
        }

        Warehouse toWarehouse = warehouseRepository.findById(request.getToWarehouseId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho nhập với ID: " + request.getToWarehouseId()));
        if (!Boolean.TRUE.equals(toWarehouse.getActive())) {
            throw new IllegalArgumentException("Kho nhập đã ngừng hoạt động");
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Phiếu chuyển kho phải có ít nhất 1 sản phẩm");
        }

        String transferCode = request.getTransferCode();
        if (transferCode == null || transferCode.isBlank()) {
            transferCode = "PCK-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        } else {
            transferCode = transferCode.trim();
            if (warehouseTransferRepository.existsByTransferCode(transferCode)) {
                throw new IllegalArgumentException("Mã phiếu chuyển kho '" + transferCode + "' đã tồn tại");
            }
        }

        WarehouseTransfer transfer = WarehouseTransfer.builder()
                .transferCode(transferCode)
                .fromWarehouse(fromWarehouse)
                .toWarehouse(toWarehouse)
                .status(WarehouseTransferStatus.IN_TRANSIT)
                .note(request.getNote())
                .createdBy(username != null ? username : "system")
                .createdAt(LocalDateTime.now())
                .dispatchedAt(LocalDateTime.now())
                .items(new ArrayList<>())
                .build();

        for (WarehouseTransferItemRequest itemReq : request.getItems()) {
            if (itemReq == null) {
                throw new IllegalArgumentException("Dòng sản phẩm chuyển kho không được để trống");
            }
            Product product = resolveProduct(itemReq.getProductId(), itemReq.getProductCode());

            String unit = itemReq.getUnit();
            if (unit == null || unit.isBlank()) {
                unit = product.getUnit();
            } else {
                unit = unit.trim();
            }

            BigDecimal qty = itemReq.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng cho sản phẩm '" + product.getCode() + "' phải lớn hơn 0");
            }

            BigDecimal factor = unitConversionService.getConversionFactor(product, unit);
            BigDecimal baseQty = qty.multiply(factor);

            // Kiểm tra tồn kho thực tế tại kho xuất
            WarehouseInventory fromInventory = inventoryRepository
                    .findByWarehouseIdAndProductId(fromWarehouse.getId(), product.getId())
                    .orElse(null);
            BigDecimal available = (fromInventory != null && fromInventory.getBaseQuantity() != null)
                    ? fromInventory.getBaseQuantity() : BigDecimal.ZERO;

            if (available.compareTo(baseQty) < 0) {
                throw new IllegalArgumentException("Kho xuất '" + fromWarehouse.getName() + "' không đủ tồn kho để chuyển sản phẩm '"
                        + product.getName() + "'. Tồn hiện tại: " + available + " " + product.getUnit()
                        + ", yêu cầu chuyển: " + baseQty + " " + product.getUnit() + " (" + qty + " " + unit + ")");
            }

            // Trừ tồn kho tại kho xuất ngay khi xuất chuyển
            fromInventory.subtractBaseQuantity(baseQty);
            inventoryRepository.save(fromInventory);

            // Ghi nhận hàng đang trên đường đến kho nhập (CHƯA cộng vào tồn kho đến!)
            WarehouseInventory toInventory = inventoryRepository
                    .findByWarehouseIdAndProductId(toWarehouse.getId(), product.getId())
                    .orElseGet(() -> WarehouseInventory.builder()
                            .warehouse(toWarehouse)
                            .product(product)
                            .baseQuantity(BigDecimal.ZERO)
                            .inTransitQuantity(BigDecimal.ZERO)
                            .baseUnit(product.getUnit())
                            .build());
            toInventory.addInTransitQuantity(baseQty);
            inventoryRepository.save(toInventory);

            // Tạo chi tiết phiếu chuyển
            WarehouseTransferItem item = WarehouseTransferItem.builder()
                    .product(product)
                    .unit(unit)
                    .quantity(qty)
                    .conversionFactor(factor)
                    .baseUnit(product.getUnit())
                    .baseQuantity(baseQty)
                    .note(itemReq.getNote())
                    .build();
            transfer.addItem(item);

            auditLogService.logAction(
                    "INVENTORY",
                    fromWarehouse.getCode() + "->" + toWarehouse.getCode() + ":" + product.getCode(),
                    "TRANSFER_DISPATCH",
                    available.toPlainString() + " " + product.getUnit(),
                    fromInventory.getBaseQuantity().toPlainString() + " " + product.getUnit(),
                    username != null ? username : "system");
        }

        WarehouseTransfer saved = warehouseTransferRepository.save(transfer);
        return mapToTransferResponse(saved);
    }

    @Override
    @Transactional
    public WarehouseTransferResponse receiveTransfer(Long transferId, WarehouseTransferReceiveRequest request, String username) {
        WarehouseTransfer transfer = warehouseTransferRepository.findById(transferId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chuyển kho với ID: " + transferId));

        if (transfer.getStatus() != WarehouseTransferStatus.IN_TRANSIT) {
            throw new IllegalStateException("Phiếu chuyển kho đang ở trạng thái '" + transfer.getStatus()
                    + "', không thể xác nhận nhận hàng");
        }

        Map<Long, WarehouseTransferReceiveItemRequest> itemReqMap = new HashMap<>();
        if (request != null && request.getItems() != null) {
            for (WarehouseTransferReceiveItemRequest rItem : request.getItems()) {
                if (rItem.getItemId() != null) {
                    itemReqMap.put(rItem.getItemId(), rItem);
                } else if (rItem.getProductId() != null) {
                    itemReqMap.put(rItem.getProductId(), rItem);
                }
            }
        }

        for (WarehouseTransferItem item : transfer.getItems()) {
            WarehouseTransferReceiveItemRequest receiveItemReq = itemReqMap.get(item.getId());
            if (receiveItemReq == null && item.getProduct() != null) {
                receiveItemReq = itemReqMap.get(item.getProduct().getId());
            }

            BigDecimal receivedQty;
            String discrepancyReason = null;
            if (receiveItemReq != null && receiveItemReq.getReceivedQuantity() != null) {
                receivedQty = receiveItemReq.getReceivedQuantity();
                if (receivedQty.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Số lượng nhận không được âm");
                }
                discrepancyReason = receiveItemReq.getDiscrepancyReason();
            } else {
                receivedQty = item.getQuantity(); // Mặc định nhận đủ
            }

            BigDecimal factor = item.getConversionFactor();
            BigDecimal receivedBaseQty = receivedQty.multiply(factor);
            BigDecimal discrepancyQty = item.getQuantity().subtract(receivedQty);
            BigDecimal discrepancyBaseQty = item.getBaseQuantity().subtract(receivedBaseQty);

            // Kiểm tra chênh lệch: nếu có chênh lệch thì BẮT BUỘC phải nhập lý do!
            if (discrepancyBaseQty.compareTo(BigDecimal.ZERO) != 0) {
                if (discrepancyReason == null || discrepancyReason.isBlank()) {
                    throw new IllegalArgumentException("Chênh lệch khi nhận hàng cho sản phẩm '"
                            + item.getProduct().getName() + "' bắt buộc phải nhập lý do (Xuất: "
                            + item.getQuantity() + " " + item.getUnit() + ", Nhận: " + receivedQty + " " + item.getUnit() + ")");
                }
                item.setDiscrepancyReason(discrepancyReason.trim());
            }

            item.setReceivedQuantity(receivedQty);
            item.setReceivedBaseQuantity(receivedBaseQty);
            item.setDiscrepancyQuantity(discrepancyQty);
            item.setDiscrepancyBaseQuantity(discrepancyBaseQty);

            // Cập nhật tồn kho tại kho đến:
            // 1. Trừ số lượng hàng đang trên đường (baseQuantity ban đầu đã xuất)
            // 2. Chỉ cộng số lượng thực tế nhận được (receivedBaseQty) vào tồn kho cơ sở
            WarehouseInventory toInventory = inventoryRepository
                    .findByWarehouseIdAndProductId(transfer.getToWarehouse().getId(), item.getProduct().getId())
                    .orElseGet(() -> WarehouseInventory.builder()
                            .warehouse(transfer.getToWarehouse())
                            .product(item.getProduct())
                            .baseQuantity(BigDecimal.ZERO)
                            .inTransitQuantity(BigDecimal.ZERO)
                            .baseUnit(item.getBaseUnit())
                            .build());

            BigDecimal beforeStock = toInventory.getBaseQuantity() != null ? toInventory.getBaseQuantity() : BigDecimal.ZERO;
            toInventory.subtractInTransitQuantity(item.getBaseQuantity());
            toInventory.addBaseQuantity(receivedBaseQty);
            inventoryRepository.save(toInventory);

            auditLogService.logAction(
                    "INVENTORY",
                    transfer.getToWarehouse().getCode() + ":" + item.getProduct().getCode(),
                    "TRANSFER_RECEIVE",
                    beforeStock.toPlainString() + " " + item.getBaseUnit(),
                    toInventory.getBaseQuantity().toPlainString() + " " + item.getBaseUnit(),
                    username != null ? username : "system");
        }

        transfer.setStatus(WarehouseTransferStatus.COMPLETED);
        transfer.setReceivedBy(username != null ? username : "system");
        transfer.setReceivedAt(LocalDateTime.now());
        if (request != null && request.getNote() != null && !request.getNote().isBlank()) {
            transfer.setReceiveNote(request.getNote().trim());
        }

        WarehouseTransfer saved = warehouseTransferRepository.save(transfer);
        return mapToTransferResponse(saved);
    }

    @Override
    @Transactional
    public WarehouseTransferResponse cancelTransfer(Long transferId, String reason, String username) {
        WarehouseTransfer transfer = warehouseTransferRepository.findById(transferId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chuyển kho với ID: " + transferId));

        if (transfer.getStatus() != WarehouseTransferStatus.IN_TRANSIT) {
            throw new IllegalStateException("Chỉ phiếu chuyển kho đang ở trạng thái 'IN_TRANSIT' mới có thể hủy");
        }

        for (WarehouseTransferItem item : transfer.getItems()) {
            // Hoàn lại tồn kho cho kho xuất
            WarehouseInventory fromInventory = inventoryRepository
                    .findByWarehouseIdAndProductId(transfer.getFromWarehouse().getId(), item.getProduct().getId())
                    .orElseGet(() -> WarehouseInventory.builder()
                            .warehouse(transfer.getFromWarehouse())
                            .product(item.getProduct())
                            .baseQuantity(BigDecimal.ZERO)
                            .inTransitQuantity(BigDecimal.ZERO)
                            .baseUnit(item.getBaseUnit())
                            .build());
            fromInventory.addBaseQuantity(item.getBaseQuantity());
            inventoryRepository.save(fromInventory);

            // Xóa hàng đang trên đường tại kho nhập
            WarehouseInventory toInventory = inventoryRepository
                    .findByWarehouseIdAndProductId(transfer.getToWarehouse().getId(), item.getProduct().getId())
                    .orElse(null);
            if (toInventory != null) {
                toInventory.subtractInTransitQuantity(item.getBaseQuantity());
                inventoryRepository.save(toInventory);
            }

            auditLogService.logAction(
                    "INVENTORY",
                    transfer.getFromWarehouse().getCode() + ":" + item.getProduct().getCode(),
                    "TRANSFER_CANCEL",
                    "inTransit-" + item.getBaseQuantity(),
                    fromInventory.getBaseQuantity().toPlainString() + " " + item.getBaseUnit(),
                    username != null ? username : "system");
        }

        transfer.setStatus(WarehouseTransferStatus.CANCELLED);
        transfer.setCancelledBy(username != null ? username : "system");
        transfer.setCancelledAt(LocalDateTime.now());
        transfer.setCancelReason(reason != null ? reason.trim() : null);

        WarehouseTransfer saved = warehouseTransferRepository.save(transfer);
        return mapToTransferResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseTransferResponse> getTransfers(Long fromWarehouseId, Long toWarehouseId, WarehouseTransferStatus status) {
        List<WarehouseTransfer> list = warehouseTransferRepository.findAllByOrderByCreatedAtDesc();
        return list.stream()
                .filter(t -> fromWarehouseId == null || (t.getFromWarehouse() != null && Objects.equals(t.getFromWarehouse().getId(), fromWarehouseId)))
                .filter(t -> toWarehouseId == null || (t.getToWarehouse() != null && Objects.equals(t.getToWarehouse().getId(), toWarehouseId)))
                .filter(t -> status == null || t.getStatus() == status)
                .map(this::mapToTransferResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseTransferResponse getTransferById(Long id) {
        WarehouseTransfer transfer = warehouseTransferRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu chuyển kho với ID: " + id));
        return mapToTransferResponse(transfer);
    }

    private WarehouseTransferResponse mapToTransferResponse(WarehouseTransfer transfer) {
        List<WarehouseTransferItemResponse> items = transfer.getItems() != null
                ? transfer.getItems().stream().map(this::mapToTransferItemResponse).collect(Collectors.toList())
                : Collections.emptyList();

        String statusDesc = switch (transfer.getStatus()) {
            case IN_TRANSIT -> "Đang trên đường";
            case COMPLETED -> "Đã nhận hàng";
            case CANCELLED -> "Đã hủy";
        };

        return WarehouseTransferResponse.builder()
                .id(transfer.getId())
                .transferCode(transfer.getTransferCode())
                .fromWarehouseId(transfer.getFromWarehouse() != null ? transfer.getFromWarehouse().getId() : null)
                .fromWarehouseCode(transfer.getFromWarehouse() != null ? transfer.getFromWarehouse().getCode() : null)
                .fromWarehouseName(transfer.getFromWarehouse() != null ? transfer.getFromWarehouse().getName() : null)
                .toWarehouseId(transfer.getToWarehouse() != null ? transfer.getToWarehouse().getId() : null)
                .toWarehouseCode(transfer.getToWarehouse() != null ? transfer.getToWarehouse().getCode() : null)
                .toWarehouseName(transfer.getToWarehouse() != null ? transfer.getToWarehouse().getName() : null)
                .status(transfer.getStatus())
                .statusDescription(statusDesc)
                .note(transfer.getNote())
                .receiveNote(transfer.getReceiveNote())
                .cancelReason(transfer.getCancelReason())
                .createdBy(transfer.getCreatedBy())
                .createdAt(transfer.getCreatedAt())
                .dispatchedAt(transfer.getDispatchedAt())
                .receivedBy(transfer.getReceivedBy())
                .receivedAt(transfer.getReceivedAt())
                .cancelledBy(transfer.getCancelledBy())
                .cancelledAt(transfer.getCancelledAt())
                .items(items)
                .build();
    }

    private WarehouseTransferItemResponse mapToTransferItemResponse(WarehouseTransferItem item) {
        return WarehouseTransferItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                .productCode(item.getProduct() != null ? item.getProduct().getCode() : null)
                .productName(item.getProduct() != null ? item.getProduct().getName() : null)
                .unit(item.getUnit())
                .quantity(item.getQuantity())
                .conversionFactor(item.getConversionFactor())
                .baseUnit(item.getBaseUnit())
                .baseQuantity(item.getBaseQuantity())
                .receivedQuantity(item.getReceivedQuantity())
                .receivedBaseQuantity(item.getReceivedBaseQuantity())
                .discrepancyQuantity(item.getDiscrepancyQuantity())
                .discrepancyBaseQuantity(item.getDiscrepancyBaseQuantity())
                .discrepancyReason(item.getDiscrepancyReason())
                .note(item.getNote())
                .build();
    }
}
