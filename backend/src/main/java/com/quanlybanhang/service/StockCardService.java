package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.StockCardEntryResponse;
import com.quanlybanhang.dto.response.StockCardResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Dịch vụ thẻ kho (S6-02): lấy toàn bộ phát sinh nhập, xuất, chuyển, điều chỉnh
 * của một sản phẩm theo thứ tự thời gian, kèm tồn tích lũy sau mỗi sự kiện
 * và liên kết tới chứng từ gốc.
 *
 * <p>Permission: {@code WAREHOUSE_VIEW} hoặc {@code ROLE_ADMIN}.
 * Phạm vi kho áp theo phân công giống {@link StockLedgerService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class StockCardService {

    private final WarehouseVoucherItemRepository voucherItemRepo;
    private final WarehouseTransferItemRepository transferItemRepo;
    private final ProductRepository productRepo;
    private final StockLedgerService ledgerService; // dùng lại visibleWarehousesFor()
    private final WarehouseInventoryRepository inventoryRepo;
    private final WarehouseStockCountItemRepository countItemRepo;
    private final OrderItemRepository orderItemRepo;
    private final OrderStatusHistoryRepository historyRepo;
    private final DeliveryTripStopItemRepository returnItemRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DealerReturnItemRepository dealerReturnItemRepo;

    public Map<String,Object> options() {
        var warehouses=ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW");
        return Map.of("warehouses",warehouses.stream().map(w->Map.of("id",w.getId(),"code",w.getCode(),"name",w.getName())).toList(),
                "products",productRepo.findAll().stream().sorted(Comparator.comparing(Product::getCode)).map(p->Map.of("id",p.getId(),"code",p.getCode(),"name",p.getName())).toList(),
                "pageSizes",List.of(20,50,100),"defaultPageSize",50);
    }

    /** Warehouse-scoped, read-only original-document projection for the selected SKU. No sales prices/PII. */
    public Map<String,Object> source(Long productId,String sourceType,Long sourceId,Long warehouseId) {
        if(sourceId==null || sourceId<1 || !Set.of("VOUCHER","TRANSFER","STOCK_COUNT","ORDER","DELIVERY_RETURN","DEALER_RETURN").contains(sourceType))
            throw new IllegalArgumentException("Chứng từ không hợp lệ");
        var scope=ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW").stream().map(Warehouse::getId).toList();
        if(warehouseId!=null && !scope.contains(warehouseId))throw new AccessDeniedException("Kho ngoài phạm vi");
        var product=productRepo.findById(productId).orElseThrow(()->new IllegalArgumentException("Không tìm thấy sản phẩm"));
        var entries=scope.isEmpty()?List.<StockCardEntryResponse>of():buildAllEntries(product,warehouseId==null?scope:List.of(warehouseId),null,null).stream()
                .filter(e->sourceType.equals(e.sourceType()) && sourceId.equals(e.sourceId())).toList();
        if(entries.isEmpty())throw new AccessDeniedException("Không có chứng từ của SKU này trong phạm vi kho");
        return Map.of("sourceType",sourceType,"sourceId",sourceId,"sourceCode",entries.get(0).sourceCode(),
                "productCode",product.getCode(),"productName",product.getName(),"entries",entries);
    }

    /**
     * Lấy thẻ kho phân trang của một sản phẩm.
     *
     * @param productId  ID sản phẩm (bắt buộc)
     * @param warehouseId ID kho lọc (null = tất cả kho trong phạm vi người dùng)
     * @param fromDate   Từ ngày (null = không giới hạn)
     * @param toDate     Đến ngày (null = không giới hạn)
     * @param page       Trang (0-based)
     * @param size       Số dòng mỗi trang (1–500)
     */
    public StockCardResponse getStockCard(Long productId, Long warehouseId,
            LocalDateTime fromDate, LocalDateTime toDate, int page, int size) {

        if (productId == null || productId < 1)
            throw new IllegalArgumentException("productId không hợp lệ");
        if (page < 0 || size < 1 || size > 500)
            throw new IllegalArgumentException("Trang phải không âm; số dòng mỗi trang từ 1 đến 500");
        if (warehouseId != null && warehouseId < 1)
            throw new IllegalArgumentException("warehouseId không hợp lệ");
        if(fromDate!=null && toDate!=null && fromDate.isAfter(toDate))throw new IllegalArgumentException("Từ ngày không được sau đến ngày");

        // Permission + phạm vi kho
        List<Warehouse> allowed = ledgerService.visibleWarehousesFor("WAREHOUSE_VIEW");
        List<Long> allowedIds = allowed.stream().map(Warehouse::getId).toList();
        if (warehouseId != null && !allowedIds.contains(warehouseId))
            throw new AccessDeniedException("Bạn không có quyền xem thẻ kho của kho này");

        List<Long> scopeIds = warehouseId != null ? List.of(warehouseId) : allowedIds;
        if (scopeIds.isEmpty())
            return emptyCard(productId, page, size, fromDate, toDate);

        // Lấy thông tin sản phẩm
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm: " + productId));

        // Thu thập tất cả sự kiện
        List<StockCardEntryResponse> history = buildAllEntries(product, scopeIds, null, null);
        List<StockCardEntryResponse> allEntries = history.stream()
                .filter(e->fromDate==null || !e.eventTime().isBefore(fromDate))
                .filter(e->toDate==null || !e.eventTime().isAfter(toDate)).toList();

        // Tính tổng phân trang
        int total = allEntries.size();
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / size);
        int safePageIndex = Math.min(page, Math.max(0, totalPages - 1));

        // Balance trước trang hiện tại (opening balance)
        BigDecimal openingBalance = allEntries.isEmpty()
                ? history.stream().filter(e->toDate==null || !e.eventTime().isAfter(toDate)).reduce((a,b)->b).map(StockCardEntryResponse::runningBaseBalance)
                    .orElseGet(()->history.isEmpty()?inventoryRepo.findByProductId(productId).stream().filter(i->scopeIds.contains(i.getWarehouse().getId())).map(WarehouseInventory::getBaseQuantity).reduce(BigDecimal.ZERO,BigDecimal::add)
                        :history.get(0).runningBaseBalance().subtract(history.get(0).baseQuantityChange()))
                : allEntries.get(safePageIndex*size).runningBaseBalance().subtract(allEntries.get(safePageIndex*size).baseQuantityChange());

        // Lấy trang
        int fromIdx = safePageIndex * size;
        int toIdx = Math.min(fromIdx + size, total);
        List<StockCardEntryResponse> pageEntries = allEntries.subList(fromIdx, toIdx);

        // Gán rowNumber trong trang
        List<StockCardEntryResponse> numbered = new ArrayList<>(pageEntries.size());
        for (int i = 0; i < pageEntries.size(); i++) {
            StockCardEntryResponse e = pageEntries.get(i);
            numbered.add(new StockCardEntryResponse(
                    fromIdx + i + 1,
                    e.eventTime(), e.eventType(), e.eventTypeLabel(),
                    e.baseQuantityChange(), e.runningBaseBalance(),
                    e.baseUnit(), e.displayQuantity(), e.displayUnit(),
                    e.warehouseId(), e.warehouseCode(), e.warehouseName(),
                    e.sourceType(), e.sourceId(), e.sourceCode(), e.note()));
        }

        return new StockCardResponse(
                product.getId(), product.getCode(), product.getName(), product.getUnit(),
                numbered, total, totalPages, safePageIndex, size,
                safePageIndex == 0 && fromDate==null && openingBalance.signum()==0 ? null : openingBalance, fromDate, toDate);
    }

    // -------------------------------------------------------------------------
    // Nội bộ
    // -------------------------------------------------------------------------

    private StockCardResponse emptyCard(Long productId, int page, int size,
            LocalDateTime fromDate, LocalDateTime toDate) {
        Product p = productRepo.findById(productId).orElse(null);
        String code = p != null ? p.getCode() : null;
        String name = p != null ? p.getName() : null;
        String unit = p != null ? p.getUnit() : null;
        return new StockCardResponse(productId, code, name, unit,
                List.of(), 0, 1, 0, size, null, fromDate, toDate);
    }

    /**
     * Xây danh sách phát sinh hợp nhất, đã sắp theo thời gian, với tồn tích lũy.
     * Mỗi phiếu kho cho 1 sự kiện; mỗi phiếu chuyển cho tối đa 2 sự kiện
     * (DISPATCH + RECEIVE/CANCEL) tùy trạng thái.
     */
    private List<StockCardEntryResponse> buildAllEntries(Product product,
            List<Long> scopeIds, LocalDateTime fromDate, LocalDateTime toDate) {

        // 1. Lấy sự kiện từ phiếu kho
        List<RawEntry> raw = new ArrayList<>();

        for(var i:countItemRepo.findForStockCard(product.getId(),scopeIds)){
            var c=i.getStockCount();var w=c.getWarehouse();
            if(c.getCompletedAt()!=null && scopeIds.contains(w.getId()))raw.add(new RawEntry(c.getCompletedAt(),"ADJUSTMENT","Điều chỉnh kiểm kê",
                    i.getDifferenceQuantity(),i.getBaseUnit(),i.getDifferenceQuantity(),i.getBaseUnit(),w.getId(),w.getCode(),w.getName(),"STOCK_COUNT",c.getId(),c.getCode(),c.getAdjustmentReason()));
        }
        Map<Long,List<OrderStatusHistory>> histories=new HashMap<>();
        for(var i:orderItemRepo.findForStockCard(product.getId(),scopeIds)){
            var o=i.getOrder();var w=o.getWarehouse();
            var export=histories.computeIfAbsent(o.getId(),id->historyRepo.findByOrderIdOrderByChangedAtAsc(id)).stream()
                    .filter(h->Set.of("EXPORTED","SHIPPED").contains(h.getNewStatus().toUpperCase(Locale.ROOT))).findFirst();
            if(export.isPresent() && scopeIds.contains(w.getId()))raw.add(new RawEntry(export.get().getChangedAt(),"EXPORT","Xuất theo đơn hàng",
                    i.getBaseQuantity().negate(),i.getBaseUnit(),i.getQuantity(),i.getUnit(),w.getId(),w.getCode(),w.getName(),"ORDER",o.getId(),o.getOrderCode(),export.get().getNote()));
        }
        for(var i:returnItemRepo.findForStockCard(product.getId(),scopeIds)){
            var s=i.getStop();var t=s.getTrip();var w=t.getWarehouse();
            if(scopeIds.contains(w.getId()))raw.add(new RawEntry(s.getReturnedAt(),"IMPORT","Nhập trả chuyến giao",
                    i.getQuantity(),product.getUnit(),i.getQuantity(),product.getUnit(),w.getId(),w.getCode(),w.getName(),"DELIVERY_RETURN",s.getId(),t.getTripCode(),s.getNote()));
        }
        if(dealerReturnItemRepo!=null){
            for(var i:dealerReturnItemRepo.findForStockCard(product.getId(),scopeIds)){
                var r=i.getDealerReturn();var w=i.getTargetWarehouse();
                if(r.getConfirmedAt()!=null && w!=null && scopeIds.contains(w.getId()))raw.add(new RawEntry(r.getConfirmedAt(),"IMPORT","Nhập hàng đại lý trả",
                        i.getBaseReturnQuantity(),i.getBaseUnit(),i.getReturnQuantity(),i.getUnit(),w.getId(),w.getCode(),w.getName(),"DEALER_RETURN",r.getId(),r.getReturnCode(),i.getReturnReason()));
            }
        }

        List<WarehouseVoucherItem> voucherItems =
                voucherItemRepo.findForStockCard(product.getId(), scopeIds, null, null);
        for (WarehouseVoucherItem vi : voucherItems) {
            WarehouseVoucher v = vi.getVoucher();
            LocalDateTime eventTime = v.getRecordedAt() != null ? v.getRecordedAt() : v.getVoucherDate();

            // Áp bộ lọc ngày tại Java (query JPQL đã lọc null nhưng cần lọc chính xác với fromDate/toDate)
            if (fromDate != null && eventTime.isBefore(fromDate)) continue;
            if (toDate != null && eventTime.isAfter(toDate)) continue;
            if (!scopeIds.contains(v.getWarehouse().getId())) continue;

            BigDecimal change;
            String eventType;
            String label;
            switch (v.getType()) {
                case IMPORT -> { change = vi.getBaseQuantity(); eventType = "IMPORT"; label = "Nhập kho"; }
                case EXPORT -> { change = vi.getBaseQuantity().negate(); eventType = "EXPORT"; label = "Xuất kho"; }
                case ADJUSTMENT -> {
                    change = vi.getBaseQuantity(); // Signed adjustment movement, including negative differences.
                    eventType = "ADJUSTMENT"; label = "Điều chỉnh";
                }
                default -> { change = vi.getBaseQuantity(); eventType = "VOUCHER"; label = "Phiếu kho"; }
            }

            raw.add(new RawEntry(
                    eventTime,
                    eventType, label,
                    change,
                    vi.getBaseUnit(),
                    vi.getQuantity(), vi.getUnit(),
                    v.getWarehouse().getId(), v.getWarehouse().getCode(), v.getWarehouse().getName(),
                    "VOUCHER", v.getId(), v.getVoucherCode(),
                    v.getNote() != null ? v.getNote() : vi.getNote()));
        }

        // 2. Lấy sự kiện từ phiếu chuyển kho
        List<WarehouseTransferItem> transferItems =
                transferItemRepo.findForStockCard(product.getId(), scopeIds, null, null);

        for (WarehouseTransferItem ti : transferItems) {
            WarehouseTransfer t = ti.getTransfer();
            WarehouseTransferStatus status = t.getStatus();

            Long fromWh = t.getFromWarehouse().getId();
            Long toWh = t.getToWarehouse().getId();
            boolean fromInScope = scopeIds.contains(fromWh);
            boolean toInScope = scopeIds.contains(toWh);

            // Sự kiện DISPATCH (xuất khỏi kho đi) — luôn có nếu kho đi trong phạm vi
            // (phiếu CANCELLED cũng có dispatch tại dispatchedAt, sau đó có CANCEL)
            if (fromInScope && t.getDispatchedAt() != null) {
                LocalDateTime dispatchTime = t.getDispatchedAt();
                boolean afterFrom = fromDate == null || !dispatchTime.isBefore(fromDate);
                boolean beforeTo = toDate == null || !dispatchTime.isAfter(toDate);
                if (afterFrom && beforeTo) {
                    raw.add(new RawEntry(
                            dispatchTime,
                            "TRANSFER_DISPATCH", "Xuất chuyển kho",
                            ti.getBaseQuantity().negate(),
                            ti.getBaseUnit(),
                            ti.getQuantity(), ti.getUnit(),
                            fromWh, t.getFromWarehouse().getCode(), t.getFromWarehouse().getName(),
                            "TRANSFER", t.getId(), t.getTransferCode(),
                            t.getNote()));
                }
            }

            // Sự kiện RECEIVE (nhập vào kho đến) — chỉ khi COMPLETED
            if (toInScope && status == WarehouseTransferStatus.COMPLETED && t.getReceivedAt() != null) {
                LocalDateTime receiveTime = t.getReceivedAt();
                boolean afterFrom = fromDate == null || !receiveTime.isBefore(fromDate);
                boolean beforeTo = toDate == null || !receiveTime.isAfter(toDate);
                if (afterFrom && beforeTo) {
                    BigDecimal received = ti.getReceivedBaseQuantity() != null
                            ? ti.getReceivedBaseQuantity() : ti.getBaseQuantity();
                    BigDecimal displayQty = ti.getReceivedQuantity() != null
                            ? ti.getReceivedQuantity() : ti.getQuantity();
                    raw.add(new RawEntry(
                            receiveTime,
                            "TRANSFER_RECEIVE", "Nhận chuyển kho",
                            received,
                            ti.getBaseUnit(),
                            displayQty, ti.getUnit(),
                            toWh, t.getToWarehouse().getCode(), t.getToWarehouse().getName(),
                            "TRANSFER", t.getId(), t.getTransferCode(),
                            t.getReceiveNote() != null ? t.getReceiveNote() : t.getNote()));
                }
            }

            // Sự kiện CANCEL (hoàn tồn về kho đi) — chỉ khi CANCELLED
            if (fromInScope && status == WarehouseTransferStatus.CANCELLED && t.getCancelledAt() != null) {
                LocalDateTime cancelTime = t.getCancelledAt();
                boolean afterFrom = fromDate == null || !cancelTime.isBefore(fromDate);
                boolean beforeTo = toDate == null || !cancelTime.isAfter(toDate);
                if (afterFrom && beforeTo) {
                    raw.add(new RawEntry(
                            cancelTime,
                            "TRANSFER_CANCEL", "Hủy chuyển kho (hoàn tồn)",
                            ti.getBaseQuantity(), // hoàn lại dương
                            ti.getBaseUnit(),
                            ti.getQuantity(), ti.getUnit(),
                            fromWh, t.getFromWarehouse().getCode(), t.getFromWarehouse().getName(),
                            "TRANSFER", t.getId(), t.getTransferCode(),
                            t.getCancelReason() != null ? t.getCancelReason() : t.getNote()));
                }
            }
        }

        // Sắp theo thời gian tăng dần; cùng thời gian thì theo sourceId rồi sourceType
        raw.sort(Comparator.comparing(RawEntry::eventTime)
                .thenComparing(RawEntry::sourceId)
                .thenComparing(RawEntry::eventType));

        // Tính tồn tích lũy (running balance)
        // Reconcile opening stock (seed/migrated balances) against the current physical stock.
        // Never invent a synthetic voucher or let a date filter reset the balance.
        var stocks=inventoryRepo.findByProductId(product.getId()).stream().filter(i->scopeIds.contains(i.getWarehouse().getId())).toList();
        BigDecimal balance = stocks.isEmpty()?BigDecimal.ZERO:stocks.stream().map(WarehouseInventory::getBaseQuantity).reduce(BigDecimal.ZERO,BigDecimal::add)
                .subtract(raw.stream().map(RawEntry::change).reduce(BigDecimal.ZERO,BigDecimal::add));
        List<StockCardEntryResponse> result = new ArrayList<>(raw.size());
        for (RawEntry e : raw) {
            balance = balance.add(e.change());
            result.add(new StockCardEntryResponse(
                    0, // rowNumber assigned later
                    e.eventTime(), e.eventType(), e.label(),
                    e.change(), balance,
                    e.baseUnit(), e.displayQty(), e.displayUnit(),
                    e.warehouseId(), e.warehouseCode(), e.warehouseName(),
                    e.sourceType(), e.sourceId(), e.sourceCode(), e.note()));
        }
        return result;
    }

    /** Bản ghi nội bộ tạm trước khi tính running balance. */
    private record RawEntry(
            LocalDateTime eventTime, String eventType, String label,
            BigDecimal change, String baseUnit,
            BigDecimal displayQty, String displayUnit,
            Long warehouseId, String warehouseCode, String warehouseName,
            String sourceType, Long sourceId, String sourceCode, String note) {
    }
}
