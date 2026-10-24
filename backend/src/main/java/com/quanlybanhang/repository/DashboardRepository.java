package com.quanlybanhang.repository;

import com.quanlybanhang.dto.response.DashboardResponse.*;
import com.quanlybanhang.model.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** Aggregates only: no entity graph loading, per-dealer lookups or application-side order scans. */
@Repository
@RequiredArgsConstructor
public class DashboardRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private MapSqlParameterSource parameters(LocalDate today) {
        return new MapSqlParameterSource("statuses", OrderStatus.debtBearingStatuses())
                .addValue("monthStart", today.withDayOfMonth(1).atStartOfDay())
                .addValue("tomorrow", today.plusDays(1).atStartOfDay())
                .addValue("today", today.atStartOfDay());
    }
    public List<DailySales> dailySales(LocalDate today) {
        return jdbc.query("""
            select cast(coalesce(o.recorded_at,o.created_at) as date) as sale_day,
                   sum(coalesce(o.total_amount,0)) as sales
            from orders o where o.status in (:statuses)
              and coalesce(o.recorded_at,o.created_at) >= :monthStart
              and coalesce(o.recorded_at,o.created_at) < :tomorrow
            group by cast(coalesce(o.recorded_at,o.created_at) as date) order by sale_day
            """, parameters(today), (r,n) -> new DailySales(r.getDate("sale_day").toLocalDate(),r.getBigDecimal("sales")));
    }
    public List<TerritorySales> territorySales(LocalDate today) {
        return jdbc.query("""
            select t.id as territory_id, coalesce(t.name,'Chưa phân khu vực') as territory_name,
                   sum(coalesce(o.total_amount,0)) as sales
            from orders o left join dealers d on d.id=o.dealer_id left join territories t on t.id=d.territory_id
            where o.status in (:statuses) and coalesce(o.recorded_at,o.created_at) >= :monthStart
              and coalesce(o.recorded_at,o.created_at) < :tomorrow
            group by t.id,t.name order by sales desc,t.id
            """, parameters(today), (r,n) -> new TerritorySales(nullableId(r,"territory_id"),r.getString("territory_name"),r.getBigDecimal("sales")));
    }
    public record Backlog(long approval, long export) {}
    public Backlog backlog() {
        return jdbc.queryForObject("""
            select coalesce(sum(case when status='PENDING_APPROVAL' then 1 else 0 end),0) as approval,
                   coalesce(sum(case when status in ('APPROVED','RECORDED','PICKING','PROCESSING') then 1 else 0 end),0) as pending_export
            from orders where status in ('PENDING_APPROVAL','APPROVED','RECORDED','PICKING','PROCESSING')
            """, Map.of(), (r,n) -> new Backlog(r.getLong("approval"),r.getLong("pending_export")));
    }
    public long belowMinimumSkus() {
        // Start from configured minimums, rather than warehouses CROSS JOIN products.
        return jdbc.queryForObject("""
            select count(distinct p.id)
            from warehouse_stock_minimums m join warehouses w on w.id=m.warehouse_id
            join products p on p.id=m.product_id
            left join warehouse_inventories i on i.warehouse_id=m.warehouse_id and i.product_id=m.product_id
            where
            """ + StockLedgerRepository.BELOW_MINIMUM, Map.of(), Long.class);
    }
    private static final String OVERDUE = """
        select d.id as dealer_id,d.code as dealer_code,d.name as dealer_name,
               t.id as territory_id,t.name as territory_name,
               sum(b.unpaid) as current_debt,
               sum(case when d.max_debt_days>0 and timestampadd(DAY,d.max_debt_days,cast(b.recorded as date)) < :today
                   then b.unpaid else 0 end) as overdue_amount,
               max(case when b.unpaid>0 and d.max_debt_days>0
                   then greatest(0,timestampdiff(DAY,cast(b.recorded as date),:today)-d.max_debt_days) else 0 end) as overdue_days
        from (
          select r.dealer_id,r.recorded,
                 greatest(0,r.running_amount-coalesce(p.paid,0))
                   -greatest(0,r.running_amount-r.amount-coalesce(p.paid,0)) as unpaid
          from (
            select o.dealer_id,coalesce(o.total_amount,0) as amount,
                   coalesce(o.recorded_at,o.created_at) as recorded,
                   sum(coalesce(o.total_amount,0)) over (
                     partition by o.dealer_id order by coalesce(o.recorded_at,o.created_at),o.id
                     rows between unbounded preceding and current row) as running_amount
            from orders o where o.status in (:statuses)
          ) r left join (select dealer_id,sum(amount) as paid from debt_payments group by dealer_id) p on p.dealer_id=r.dealer_id
        ) b join dealers d on d.id=b.dealer_id left join territories t on t.id=d.territory_id
        group by d.id,d.code,d.name,t.id,t.name
        having sum(case when d.max_debt_days>0 and timestampadd(DAY,d.max_debt_days,cast(b.recorded as date)) < :today
                       then b.unpaid else 0 end)>0
        """;
    public record OverdueResult(List<OverdueDealer> dealers, BigDecimal amount, long count) {}
    public OverdueResult overdue(LocalDate today, int limit, long offset) {
        var params=parameters(today).addValue("limit",limit).addValue("offset",offset);
        record Row(OverdueDealer dealer, BigDecimal total, long count) {}
        List<Row> rows=jdbc.query("""
            select overdue.*,sum(overdue_amount) over() as total_amount,count(*) over() as total_count
            from (""" + OVERDUE + """
            ) overdue order by overdue_amount desc,dealer_id asc limit :limit offset :offset
            """,params,(r,n)-> new Row(new OverdueDealer(r.getLong("dealer_id"),r.getString("dealer_code"),r.getString("dealer_name"),
                    nullableId(r,"territory_id"),r.getString("territory_name"),r.getBigDecimal("current_debt"),
                    r.getBigDecimal("overdue_amount"),r.getInt("overdue_days")),r.getBigDecimal("total_amount"),r.getLong("total_count")));
        if (!rows.isEmpty()) return new OverdueResult(rows.stream().map(Row::dealer).toList(),rows.get(0).total(),rows.get(0).count());
        if (offset==0) return new OverdueResult(List.of(),BigDecimal.ZERO,0);
        // An out-of-range page must still return the correct total, not an empty total of zero.
        return jdbc.queryForObject("select coalesce(sum(overdue_amount),0) as total_amount,count(*) as total_count from ("+OVERDUE+") overdue",
                params,(r,n)->new OverdueResult(List.of(),r.getBigDecimal("total_amount"),r.getLong("total_count")));
    }
    private static Long nullableId(ResultSet row,String column) throws SQLException {
        long value=row.getLong(column); return row.wasNull()?null:value;
    }
}