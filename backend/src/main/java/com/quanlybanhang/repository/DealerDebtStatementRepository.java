package com.quanlybanhang.repository;

import com.quanlybanhang.model.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class DealerDebtStatementRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String EVENTS = """
        select coalesce(o.recorded_at,o.created_at) as occurred_at, 'ORDER' as source_type, 1 as source_rank,
               o.id as source_id,o.order_code as document_code,'Ghi nhận đơn hàng' as description,
               coalesce(o.total_amount,0) as amount
        from orders o where o.dealer_id=:dealerId and o.status in (:statuses)
        union all
        select cast(p.payment_date as datetime) as occurred_at,'PAYMENT' as source_type,2 as source_rank,
               p.id as source_id,p.payment_code as document_code,coalesce(p.note,'Thu tiền công nợ') as description,-p.amount as amount
        from debt_payments p where p.dealer_id=:dealerId
        union all
        select a.adjustment_date as occurred_at,'INVOICE_ADJUSTMENT' as source_type,3 as source_rank,
               a.id as source_id,a.adjustment_code as document_code,a.adjustment_reason as description,a.adjustment_amount as amount
        from invoice_adjustments a join invoices i on i.id=a.invoice_id
        where i.dealer_id=:dealerId and a.adjustment_type in ('INCREASE','DECREASE','CANCEL') and a.adjustment_amount<>0
        """;
    public record Movement(LocalDateTime occurredAt, String sourceType, Long sourceId, String documentCode,
            String description, BigDecimal amount) {}
    private MapSqlParameterSource parameters(Long dealerId) {
        return new MapSqlParameterSource("dealerId",dealerId).addValue("statuses",OrderStatus.debtBearingStatuses());
    }
    public BigDecimal opening(Long dealerId, LocalDate from) {
        return jdbc.queryForObject("select coalesce(sum(e.amount),0) from ("+EVENTS+") e where e.occurred_at<:from",
                parameters(dealerId).addValue("from",from.atStartOfDay()),BigDecimal.class);
    }
    public List<Movement> movements(Long dealerId, LocalDate from, LocalDate to, int limit) {
        return jdbc.query("select e.* from ("+EVENTS+") e where e.occurred_at>=:from and e.occurred_at<:until "
                        +"order by e.occurred_at,e.source_rank,e.source_id limit :limit",
                parameters(dealerId).addValue("from",from.atStartOfDay()).addValue("until",to.plusDays(1).atStartOfDay()).addValue("limit",limit),
                (r,n)->new Movement(r.getTimestamp("occurred_at").toLocalDateTime(),r.getString("source_type"),r.getLong("source_id"),
                        r.getString("document_code"),r.getString("description"),r.getBigDecimal("amount")));
    }
}