package com.quanlybanhang.service;

import com.quanlybanhang.dto.OrderShipmentDtos.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.model.Order;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.*;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;

/** S6-05. Lock order -> sorted physical stock -> lots. All accounting and audit are atomic. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class OrderShipmentService {
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final WarehouseRepository warehouses;
    private final UserRepository users;
    private final WarehouseInventoryRepository stocks;
    private final WarehouseLotInventoryRepository lots;
    private final WarehouseVoucherRepository vouchers;
    private final OrderStatusHistoryRepository histories;
    private final AuditLogService audit;
    private static final Set<String> READY = Set.of("APPROVED", "RECORDED", "PICKING", "PROCESSING", "EXPORTED");

    private record Access(boolean admin, boolean write, Set<Long> ids, String username) {
        boolean contains(Long id) { return id != null && (admin || ids.contains(id)); }
        void require(Long id) { if (!contains(id)) throw new AccessDeniedException("Đơn ngoài phạm vi kho được phân công"); }
    }
    private Access access(boolean write) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) throw new AccessDeniedException("Bạn chưa đăng nhập");
        Set<String> grants=new HashSet<>();auth.getAuthorities().forEach(a->grants.add(a.getAuthority()));
        boolean admin=grants.contains("ROLE_ADMIN"), mutate=admin || grants.contains("WAREHOUSE_EXPORT");
        if(write?!mutate:!mutate && !grants.contains("WAREHOUSE_VIEW")) throw new AccessDeniedException("Không có quyền xem/xác nhận xuất theo ma trận");
        if(admin)return new Access(true,true,Set.of(),auth.getName());
        var user=users.findByUsername(auth.getName()).orElseThrow(()->new AccessDeniedException("Không tìm thấy tài khoản"));
        Set<Long> ids=new HashSet<>();if(user.getWarehouses()!=null)user.getWarehouses().forEach(w->ids.add(w.getId()));
        return new Access(false,mutate,ids,auth.getName());
    }
    public Options options() {
        var a=access(false);
        return new Options(warehouses.findAllByActiveTrueOrderByNameAsc().stream().filter(w->a.contains(w.getId()))
                .map(w->new Named(w.getId(),w.getCode(),w.getName())).toList(),a.write(),4,
                "Xuất bao nhiêu trừ tồn thực và giữ chỗ bấy nhiêu. Phần còn nợ tiếp tục giữ chỗ, không tự hết hạn sau lượt xuất đầu.");
    }
    public Page<Summary> search(Long warehouseId, String queue, String keyword, int page, int size) {
        var a=access(false);
        if(page<0 || size<1 || size>100)throw bad("Trang không âm, kích thước 1–100");
        if(warehouseId!=null)a.require(warehouseId);
        if(!Set.of("READY","BACKORDER","HISTORY").contains(queue))throw bad("Bộ lọc không hợp lệ");
        if(keyword!=null && keyword.length()>150)throw bad("Từ khóa tối đa 150 ký tự");
        String term=keyword==null || keyword.isBlank()?null:"%"+keyword.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return orders.findAll((Specification<Order>)(root,q,cb)->{
            var p=new ArrayList<jakarta.persistence.criteria.Predicate>();
            if(!a.admin())p.add(a.ids().isEmpty()?cb.disjunction():root.get("warehouse").get("id").in(a.ids()));
            else p.add(cb.isNotNull(root.get("warehouse")));
            if(warehouseId!=null)p.add(cb.equal(root.get("warehouse").get("id"),warehouseId));
            if("BACKORDER".equals(queue))p.add(cb.isTrue(root.get("backordered")));
            if("READY".equals(queue)){
                p.add(root.get("status").in(List.of("APPROVED","RECORDED","PICKING","PROCESSING")));
                p.add(cb.isTrue(root.get("inventoryReserved")));
                p.add(cb.or(cb.isNull(root.get("reservationExpiresAt")),cb.greaterThan(root.get("reservationExpiresAt"),LocalDateTime.now())));
            }
            if("HISTORY".equals(queue)){
                var sub=q.subquery(Long.class);var v=sub.from(WarehouseVoucher.class);
                sub.select(v.get("id")).where(cb.equal(v.get("shipmentOrderId"),root.get("id")));p.add(cb.exists(sub));
            }
            if(term!=null)p.add(cb.like(cb.lower(root.get("orderCode")),term,'!'));
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        },PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"id"))).map(this::summary);
    }
    private Summary summary(Order o) { return new Summary(o.getId(),o.getOrderCode(),o.getCustomerName(),o.getWarehouse().getId(),
            o.getWarehouse().getName(),label(o),Boolean.TRUE.equals(o.getBackordered())); }
    public Detail get(Long id) {var a=access(false);return detail(load(id,a,false),a);}
    private Order load(Long id,Access a,boolean lock){
        if(id==null || id<1)throw bad("Mã đơn không hợp lệ");
        var o=(lock?orders.findByIdForUpdate(id):orders.findById(id)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy đơn"));
        a.require(o.getWarehouse()==null?null:o.getWarehouse().getId());return o;
    }
    private String blocked(Order o) {
        if(!READY.contains(o.getStatus()) || ("EXPORTED".equals(o.getStatus()) && !Boolean.TRUE.equals(o.getBackordered())))return "Đơn không còn ở bước được xuất kho";
        if(!Boolean.TRUE.equals(o.getWarehouse().getActive()))return "Kho đã ngừng hoạt động";
        if(!Boolean.TRUE.equals(o.getInventoryReserved()))return "Đơn chưa có tồn giữ chỗ";
        if(o.getReservationExpiresAt()!=null && !o.getReservationExpiresAt().isAfter(LocalDateTime.now()))return "Đơn đã hết hạn giữ chỗ";
        if(o.getItems().isEmpty())return "Đơn không có dòng hàng";
        return null;
    }
    private Detail detail(Order o,Access a) {
        var rows=o.getItems().stream().map(i->{
            var available=lots.findByWarehouseIdAndProductId(o.getWarehouse().getId(),i.getProduct().getId());
            return new Line(i.getId(),i.getProduct().getCode(),i.getProduct().getName(),i.getUnit(),i.getBaseUnit(),i.getConversionFactor(),
                    i.getQuantity(),i.effectiveExportedQuantity(),i.remainingExportQuantity(),Boolean.TRUE.equals(i.getProduct().getManageLot()) || available.stream().anyMatch(l->l.getBaseQuantity().signum()>0),
                    available.stream().filter(l->l.getBaseQuantity().signum()>0 && l.getExpiryDate()!=null && !l.getExpiryDate().isBefore(LocalDate.now()))
                            .sorted(Comparator.comparing(WarehouseLotInventory::getExpiryDate).thenComparing(WarehouseLotInventory::getId))
                            .map(l->new Lot(l.getLotNumber(),l.getExpiryDate(),l.getBaseQuantity())).toList());
        }).toList();
        var reason=blocked(o);
        return new Detail(o.getId(),o.getVersion(),o.getOrderCode(),o.getCustomerName(),o.getWarehouse().getName(),label(o),Boolean.TRUE.equals(o.getBackordered()),
                a.write() && reason==null,reason,rows,vouchers.findByShipmentOrderIdOrderByIdAsc(o.getId()).stream().map(this::receipt).toList());
    }
    private Receipt receipt(WarehouseVoucher v){return new Receipt(v.getId(),v.getVoucherCode(),v.getStatus(),v.getRecordedAt(),v.getCreatedBy(),v.getNote(),v.getItems().stream()
            .map(i->new ReceiptLine(i.getShipmentOrderItemId(),i.getProduct().getCode(),i.getProduct().getName(),i.getUnit(),i.getQuantity(),i.getBaseQuantity(),i.getLotNumber(),i.getNote())).toList());}
    private String label(Order o){return Boolean.TRUE.equals(o.getBackordered())?"Đã xuất một phần · Còn nợ hàng":OrderStatus.getDisplayName(o.getStatus());}

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Detail confirm(Long id,Confirm r){
        var a=access(true);var o=load(id,a,true);
        validate(r);String hash=hash(r);
        var previous=vouchers.findByShipmentRequestKey(r.requestKey());
        if(previous.isPresent()){
            var v=previous.get();
            if(!id.equals(v.getShipmentOrderId()) || !hash.equals(v.getShipmentRequestHash()))throw conflict("Mã yêu cầu đã dùng với nội dung khác");
            return detail(o,a); // Safe retry after a response was lost; never reapply stock.
        }
        if(!Objects.equals(o.getVersion(),r.version()))throw conflict("Đơn vừa thay đổi. Tải lại trước khi xác nhận");
        // Current reads also protect callers joining an older REPEATABLE_READ transaction.
        orderItems.findByOrderIdForUpdate(id);
        String reason=blocked(o);if(reason!=null)throw conflict(reason);
        Map<Long,OrderItem> orderLines=new HashMap<>();o.getItems().forEach(i->orderLines.put(i.getId(),i));
        Map<Long,BigDecimal> quantities=new TreeMap<>();
        for(var input:r.items()){
            var i=orderLines.get(input.orderItemId());if(i==null)throw bad("Dòng hàng không thuộc đơn");
            if(input.quantity().compareTo(i.remainingExportQuantity())>0)throw bad("Lượng xuất vượt phần còn nợ: "+i.getProduct().getCode());
            if(input.quantity().compareTo(i.remainingExportQuantity())<0 && clean(input.reason())==null)throw bad("Xuất thiếu phải nhập lý do cho từng dòng");
            quantities.merge(i.getProduct().getId(),base(i,input.quantity()),BigDecimal::add);
        }
        if(orderLines.size()!=r.items().size())throw bad("Gửi đủ các dòng của đơn, nhập 0 và lý do nếu chưa xuất");
        if(quantities.values().stream().noneMatch(n->n.signum()>0))throw bad("Phải xuất ít nhất một mặt hàng");
        Map<Long,WarehouseInventory> physical=new HashMap<>();
        stocks.findByWarehouseIdAndProductIdInForUpdate(o.getWarehouse().getId(),new ArrayList<>(quantities.keySet())).forEach(s->physical.put(s.getProduct().getId(),s));
        // Check against all reservations, not merely this order's quantities.
        for(var e:quantities.entrySet()){
            var s=physical.get(e.getKey());
            if(s==null || s.getReservedQuantity().compareTo(e.getValue())<0 || s.getBaseQuantity().compareTo(s.getReservedQuantity())<0)throw conflict("Tồn thực/giữ chỗ không khớp. Cần đối soát trước khi xuất");
        }
        Map<Long,List<WarehouseLotInventory>> lockedLots=new TreeMap<>();
        quantities.keySet().forEach(p->lockedLots.put(p,lots.findByWarehouseIdAndProductIdForUpdate(o.getWarehouse().getId(),p)));
        // Freeze legacy lot-management detection before another line consumes the last positive lot.
        Set<Long> productsWithLotStock=new HashSet<>();
        lockedLots.forEach((p,rows)->{if(rows.stream().anyMatch(l->l.getBaseQuantity().signum()>0))productsWithLotStock.add(p);});
        var v=WarehouseVoucher.builder().voucherCode("PXDH-"+UUID.randomUUID()).type(WarehouseVoucherType.EXPORT)
                .warehouse(o.getWarehouse()).status("CONFIRMED").shipmentOrderId(id).shipmentRequestKey(r.requestKey()).shipmentRequestHash(hash)
                .documentNumber(o.getOrderCode()).note(clean(r.note())).createdBy(a.username()).recordedAt(LocalDateTime.now()).build();
        // Initialize every line before changing the header to EXPORTED (legacy nulls are status-sensitive).
        o.getItems().forEach(i->i.setExportedQuantity(i.effectiveExportedQuantity()));
        for(var input:r.items().stream().sorted(Comparator.comparing(Input::orderItemId)).toList()){
            var i=orderLines.get(input.orderItemId());
            if(input.quantity().signum()==0){
                v.addItem(WarehouseVoucherItem.builder().product(i.getProduct()).shipmentOrderItemId(i.getId()).unit(i.getUnit())
                        .quantity(BigDecimal.ZERO).conversionFactor(i.getConversionFactor()).baseUnit(i.getBaseUnit()).baseQuantity(BigDecimal.ZERO)
                        .note(clean(input.reason())).build());
                continue;
            }
            var amount=base(i,input.quantity());var stock=physical.get(i.getProduct().getId());
            var available=lockedLots.get(i.getProduct().getId());
            boolean lotManaged=Boolean.TRUE.equals(i.getProduct().getManageLot()) || productsWithLotStock.contains(i.getProduct().getId());
            WarehouseLotInventory selected=null,suggested=null;
            if(lotManaged){
                suggested=available.stream().filter(l->l.getExpiryDate()!=null && !l.getExpiryDate().isBefore(LocalDate.now()) && l.getBaseQuantity().compareTo(amount)>=0)
                        .min(Comparator.comparing(WarehouseLotInventory::getExpiryDate).thenComparing(WarehouseLotInventory::getId)).orElse(null);
                String lotNumber=clean(input.lotNumber());
                selected=lotNumber==null?suggested:available.stream().filter(l->lotNumber.equals(l.getLotNumber())).findFirst().orElseThrow(()->bad("Không tìm thấy lô đã chọn"));
                if(selected==null || selected.getExpiryDate()==null || selected.getExpiryDate().isBefore(LocalDate.now()) || selected.getBaseQuantity().compareTo(amount)<0)
                    throw conflict("Lô không đủ tồn hoặc không còn hạn. Giảm lượng xuất hoặc chọn lô hợp lệ");
                if((suggested==null || !selected.getId().equals(suggested.getId())) && clean(input.reason())==null)throw bad("Chọn lô khác gợi ý FEFO phải có lý do");
                selected.subtractBaseQuantity(amount);lots.save(selected);
            }else if(clean(input.lotNumber())!=null)throw bad("Sản phẩm không có lô hợp lệ");
            stock.subtractBaseQuantity(amount);stock.subtractReservedQuantity(amount);stocks.save(stock);
            i.setExportedQuantity(i.getExportedQuantity().add(input.quantity()));
            v.addItem(WarehouseVoucherItem.builder().product(i.getProduct()).shipmentOrderItemId(i.getId()).unit(i.getUnit())
                    .quantity(input.quantity()).conversionFactor(i.getConversionFactor()).baseUnit(i.getBaseUnit()).baseQuantity(amount)
                    .lotNumber(selected==null?null:selected.getLotNumber()).expiryDate(selected==null?null:selected.getExpiryDate())
                    .manufacturingDate(selected==null?null:selected.getManufacturingDate()).suggestedLotNumber(suggested==null?null:suggested.getLotNumber())
                    .overrideReason(clean(input.reason())).note(clean(input.reason())).build());
        }
        boolean remaining=o.getItems().stream().anyMatch(i->i.remainingExportQuantity().signum()>0);
        String old=o.getStatus();o.setStatus("EXPORTED");o.setBackordered(remaining);o.setInventoryReserved(remaining);o.setReservationExpiresAt(null);
        o.setShipmentSequence((o.getShipmentSequence()==null?0:o.getShipmentSequence())+1);
        vouchers.saveAndFlush(v);orders.saveAndFlush(o);
        histories.save(OrderStatusHistory.builder().order(o).previousStatus(old).newStatus("EXPORTED").statusDisplayName(label(o))
                .changedBy(a.username()).changedAt(v.getRecordedAt()).note(v.getVoucherCode()+(remaining?": còn nợ hàng":": đã xuất đủ")).build());
        audit.logAction("ORDER",o.getOrderCode(),"CONFIRM_SHIPMENT",old,v.getVoucherCode()+(remaining?" · Còn nợ hàng":" · Đã xuất đủ"),a.username());
        return detail(o,a);
    }
    /** Preserve the older full-export action without a second, unsafe accounting implementation. */
    @Transactional public void confirmRemaining(Order o){
        confirm(o.getId(),new Confirm(o.getVersion(),UUID.randomUUID().toString(),"Xuất từ vòng đời đơn hàng",
                o.getItems().stream().map(i->new Input(i.getId(),i.remainingExportQuantity(),null,null)).toList()));
    }
    private void validate(Confirm r){
        if(r==null || r.version()==null || r.version()<0 || r.requestKey()==null)throw bad("Thiếu phiên bản/mã yêu cầu");
        try{if(!UUID.fromString(r.requestKey()).toString().equalsIgnoreCase(r.requestKey()))throw new IllegalArgumentException();}catch(Exception ex){throw bad("Mã yêu cầu phải là UUID");}
        if(r.note()!=null && r.note().length()>500 || r.items()==null || r.items().isEmpty() || r.items().size()>500)throw bad("Nội dung xác nhận không hợp lệ");
        Set<Long> seen=new HashSet<>();
        for(var i:r.items()){
            if(i==null || i.orderItemId()==null || !seen.add(i.orderItemId()) || i.quantity()==null || i.quantity().signum()<0
                    || i.quantity().stripTrailingZeros().scale()>4 || i.quantity().compareTo(new BigDecimal("99999999999.9999"))>0
                    || i.reason()!=null && i.reason().length()>255 || i.lotNumber()!=null && i.lotNumber().length()>50)throw bad("Dòng xuất không hợp lệ hoặc bị lặp");
        }
    }
    private BigDecimal base(OrderItem i,BigDecimal q){
        if(i.getConversionFactor()==null || i.getConversionFactor().signum()<=0)throw bad("Hệ số đóng băng không hợp lệ");
        try{
            var amount=q.multiply(i.getConversionFactor()).setScale(4,RoundingMode.UNNECESSARY);
            if(amount.compareTo(new BigDecimal("99999999999.9999"))>0)throw bad("Lượng cơ sở vượt giới hạn lưu trữ");
            return amount;
        }catch(ArithmeticException ex){throw bad("Lượng cơ sở vượt 4 số thập phân");}
    }
    private String hash(Confirm r){
        try{
            var normalized=r.items().stream().sorted(Comparator.comparing(Input::orderItemId))
                    .map(i->List.of(i.orderItemId(),i.quantity().stripTrailingZeros().toPlainString(),Objects.toString(clean(i.lotNumber()),""),Objects.toString(clean(i.reason()),""))).toList();
            byte[] bytes=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(List.of(Objects.toString(clean(r.note()),""),normalized));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch(Exception ex){throw new IllegalStateException("Không tạo được dấu vết yêu cầu",ex);}
    }
    private static String clean(String s){return s==null || s.isBlank()?null:s.trim();}
    private static ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
    private static ResponseStatusException conflict(String s){return new ResponseStatusException(HttpStatus.CONFLICT,s);}
}
