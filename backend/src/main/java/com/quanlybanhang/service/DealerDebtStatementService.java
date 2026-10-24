package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.DealerDebtStatementResponse;
import com.quanlybanhang.dto.response.DealerDebtStatementResponse.Entry;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class DealerDebtStatementService {
    private final DealerRepository dealers;
    private final UserRepository users;
    private final DealerDebtStatementRepository ledger;
    private final int maxRows;
    public DealerDebtStatementService(DealerRepository dealers,UserRepository users,DealerDebtStatementRepository ledger,
            @Value("${app.debt-statement.max-rows:20000}") int maxRows) {
        if(maxRows<1||maxRows>100000)throw new IllegalArgumentException("Giới hạn sổ công nợ phải từ 1 đến 100000 dòng");
        this.dealers=dealers;this.users=users;this.ledger=ledger;this.maxRows=maxRows;
    }
    public DealerDebtStatementResponse statement(Long dealerId,LocalDate from,LocalDate to) {
        if(dealerId==null||dealerId<1||from==null||to==null||from.isAfter(to)||from.getYear()<1000||to.getYear()>9998)
            throw new IllegalArgumentException("Mã đại lý và khoảng ngày không hợp lệ");
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated())throw new AccessDeniedException("Chưa xác thực");
        Set<String> grants=new HashSet<>();auth.getAuthorities().forEach(a->grants.add(a.getAuthority()));
        boolean admin=grants.contains("ROLE_ADMIN");
        if(!admin&&!grants.contains("FINANCE_VIEW_DEBT"))throw new AccessDeniedException("Không có quyền xem công nợ");
        Dealer dealer=dealers.findById(dealerId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy đại lý"));
        if(!admin) {
            User user=users.findByUsername(auth.getName()).orElseThrow(()->new AccessDeniedException("Không tìm thấy tài khoản"));
            if(grants.contains("ROLE_DEALER")&&(user.getDealer()==null||!dealerId.equals(user.getDealer().getId())))
                throw new AccessDeniedException("Chỉ được xem công nợ của mình");
            boolean broad=grants.contains("ROLE_SALES_MANAGER")||grants.contains("ROLE_ACCOUNTANT")||grants.contains("ROLE_VIEWER");
            if(grants.contains("ROLE_SALES")&&!broad&&(dealer.getAssignedUser()==null||!user.getId().equals(dealer.getAssignedUser().getId())))
                throw new AccessDeniedException("Đại lý không thuộc nhân viên phụ trách");
        }
        BigDecimal opening=ledger.opening(dealerId,from);
        var movements=ledger.movements(dealerId,from,to,maxRows+1);
        if(movements.size()>maxRows)throw new IllegalArgumentException("Sổ vượt giới hạn "+maxRows+" dòng. Hãy thu hẹp khoảng ngày; không xuất thiếu dữ liệu.");
        BigDecimal balance=opening,increase=BigDecimal.ZERO,decrease=BigDecimal.ZERO;
        List<Entry> entries=new ArrayList<>();
        for(var row:movements) {
            BigDecimal debit=row.amount().max(BigDecimal.ZERO),credit=row.amount().negate().max(BigDecimal.ZERO);
            balance=balance.add(row.amount());increase=increase.add(debit);decrease=decrease.add(credit);
            entries.add(new Entry(row.occurredAt(),row.sourceType(),row.sourceId(),row.documentCode(),row.description(),debit,credit,balance));
        }
        return new DealerDebtStatementResponse(dealerId,dealer.getCode(),dealer.getName(),from,to,opening,increase,decrease,balance,List.copyOf(entries));
    }
}