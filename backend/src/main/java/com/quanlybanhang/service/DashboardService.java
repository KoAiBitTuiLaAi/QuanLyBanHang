package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.DashboardResponse;
import com.quanlybanhang.dto.response.DashboardResponse.*;
import com.quanlybanhang.repository.DashboardRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class DashboardService {
    private final DashboardRepository repository;
    private final Clock clock;
    public DashboardService(DashboardRepository repository,@Qualifier("dashboardClock") Clock clock) {
        this.repository=repository; this.clock=clock;
    }
    public DashboardResponse overview(int overdueLimit) {
        validate(0,overdueLimit);
        OffsetDateTime now=OffsetDateTime.now(clock);
        LocalDate today=now.toLocalDate();
        var month=YearMonth.from(today);
        Map<LocalDate,BigDecimal> sales=new HashMap<>();
        repository.dailySales(today).forEach(row->sales.put(row.date(),row.sales()));
        List<DailySales> days=new ArrayList<>();
        for (int day=1;day<=month.lengthOfMonth();day++) {
            var date=month.atDay(day); days.add(new DailySales(date,sales.getOrDefault(date,BigDecimal.ZERO)));
        }
        var territories=repository.territorySales(today);
        var backlog=repository.backlog();
        long lowStock=repository.belowMinimumSkus();
        var overdue=repository.overdue(today,overdueLimit,0);
        var metrics=new Metrics(sales.getOrDefault(today,BigDecimal.ZERO),
                sales.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add),
                backlog.approval(),backlog.export(),overdue.amount(),lowStock);
        return new DashboardResponse(today,month,now,clock.getZone().getId(),metrics,days,territories,overdue.dealers(),overdue.count());
    }
    public Page<OverdueDealer> overdueDealers(int page,int size) {
        validate(page,size);
        var rows=repository.overdue(LocalDate.now(clock),size,(long)page*size);
        return new PageImpl<>(rows.dealers(),PageRequest.of(page,size),rows.count());
    }
    private void validate(int page,int size) {
        if(page<0||size<1||size>100)throw new IllegalArgumentException("Trang phải không âm, số dòng từ 1 đến 100");
    }
}