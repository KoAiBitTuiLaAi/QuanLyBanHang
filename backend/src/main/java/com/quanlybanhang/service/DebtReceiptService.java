package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Read-only receipt of an already persisted accounting entry; printing never allocates a code. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class DebtReceiptService {
    private final DebtPaymentRepository payments;
    private final UserRepository users;

    public DebtPaymentResponse receipt(Long id) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated())throw new AccessDeniedException("Chưa xác thực");
        Set<String> grants=new HashSet<>();auth.getAuthorities().forEach(a->grants.add(a.getAuthority()));
        boolean admin=grants.contains("ROLE_ADMIN");
        if(!admin&&!grants.contains("FINANCE_VIEW_DEBT"))throw new AccessDeniedException("Không có quyền xem/in phiếu thu");
        if(id==null||id<1)throw new IllegalArgumentException("Mã phiếu thu không hợp lệ");
        DebtPayment payment=payments.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy phiếu thu"));
        Dealer dealer=payment.getDealer();
        if(dealer==null)throw incomplete();
        if(!admin){
            User user=users.findByUsername(auth.getName()).orElseThrow(()->new AccessDeniedException("Không tìm thấy tài khoản"));
            if(grants.contains("ROLE_DEALER")&&(user.getDealer()==null||!dealer.getId().equals(user.getDealer().getId())))
                throw new AccessDeniedException("Phiếu thu không thuộc đại lý của bạn");
            boolean broad=grants.contains("ROLE_ACCOUNTANT")||grants.contains("ROLE_SALES_MANAGER")||grants.contains("ROLE_VIEWER");
            if(!broad&&grants.contains("ROLE_SALES")&&(dealer.getAssignedUser()==null||!user.getId().equals(dealer.getAssignedUser().getId())))
                throw new AccessDeniedException("Đại lý không thuộc nhân viên phụ trách");
            if(!broad&&(grants.contains("ROLE_WAREHOUSE_STAFF")||grants.contains("ROLE_WAREHOUSE_MANAGER"))
                    &&(dealer.getWarehouse()==null||user.getWarehouses()==null||user.getWarehouses().stream().noneMatch(w->w.getId().equals(dealer.getWarehouse().getId()))))
                throw new AccessDeniedException("Đại lý ngoài phạm vi kho được phân công");
        }
        String type=payment.getPaymentType()==null?"PAYMENT":payment.getPaymentType();
        if(payment.getPaymentCode()==null||payment.getPaymentCode().isBlank()||payment.getAmount()==null||payment.getPaymentDate()==null
                ||(!type.equals("PAYMENT")&&!type.equals("ADJUSTMENT"))
                ||(type.equals("PAYMENT")&&payment.getAmount().signum()<=0))throw incomplete();
        var original=payment.getOriginalPayment();
        if(type.equals("ADJUSTMENT")&&original==null)throw incomplete();
        if(original!=null&&(original.getDealer()==null||!dealer.getId().equals(original.getDealer().getId())))throw incomplete();
        var entries=payment.getAllocations()==null?List.<DebtPaymentAllocation>of():payment.getAllocations();
        if(entries.size()>5000)throw new IllegalArgumentException("Phiếu vượt giới hạn 5000 dòng; cần đối soát trước khi xuất chứng từ");
        var allocations=entries.stream().sorted(Comparator.comparing(DebtPaymentAllocation::getId,Comparator.nullsLast(Comparator.naturalOrder())))
                .map(a->{
                    var order=a.getOrder();
                    if(order==null||order.getDealer()==null||!dealer.getId().equals(order.getDealer().getId()))throw incomplete();
                    return DebtPaymentAllocationResponse.builder().id(a.getId()).orderId(order.getId())
                            .orderCode(a.getOrderCode()==null?order.getOrderCode():a.getOrderCode())
                            .allocatedAmount(a.getAmount()).allocatedAt(a.getCreatedAt()).note(a.getNote()).build();
                }).toList();
        return DebtPaymentResponse.builder().id(payment.getId()).paymentCode(payment.getPaymentCode()).paymentType(type)
                .dealerId(dealer.getId()).dealerCode(dealer.getCode()).dealerName(dealer.getName())
                .amount(payment.getAmount()).paymentDate(payment.getPaymentDate()).paymentMethod(payment.getPaymentMethod())
                .recordedBy(payment.getRecordedBy()).referenceCode(payment.getReferenceCode()).note(payment.getNote()).createdAt(payment.getCreatedAt())
                .originalPaymentId(original==null?null:original.getId()).originalPaymentCode(original==null?null:original.getPaymentCode())
                .adjustmentReason(payment.getAdjustmentReason()).allocations(allocations).build();
    }
    private ResponseStatusException incomplete(){return new ResponseStatusException(HttpStatus.CONFLICT,"Chứng từ thiếu hoặc không khớp dữ liệu gốc; cần đối soát trước khi in");}
}
