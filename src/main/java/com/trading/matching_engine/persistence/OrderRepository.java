package com.trading.matching_engine.persistence;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderSnapshot;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent by design — the writer replays a batch after a transient failure. */
    public void batchInsert(List<OrderSnapshot> orders) {
        String sql = """
            INSERT INTO orders
              (id, symbol, side, order_type, price,
               original_quantity, remaining_quantity, status,
               client_order_id, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (id) DO UPDATE
              SET remaining_quantity = EXCLUDED.remaining_quantity,
                  status             = EXCLUDED.status,
                  updated_at         = EXCLUDED.updated_at
            """;

        Timestamp now = Timestamp.from(Instant.now());
        jdbc.batchUpdate(sql, orders, orders.size(), (ps, o) -> {
            ps.setString(1, o.id());
            ps.setString(2, o.symbol());
            ps.setString(3, o.side().name());
            ps.setString(4, o.orderType().name());
            ps.setBigDecimal(5, o.price());
            ps.setLong(6, o.originalQuantity());
            ps.setLong(7, o.remainingQuantity());
            ps.setString(8, o.status().name());
            ps.setString(9, o.clientOrderId());
            ps.setTimestamp(10, Timestamp.from(o.createdAt()));
            ps.setTimestamp(11, now);
        });
    }

    /**
     * Every order still resting in the book, oldest first — time priority is rebuilt by
     * replaying them in creation order.
     */
    public List<Order> findOpenOrders() {
        String sql = """
            SELECT * FROM orders
            WHERE status IN ('NEW', 'PARTIALLY_FILLED')
              AND order_type = 'LIMIT'
              AND remaining_quantity > 0
            ORDER BY created_at ASC, id ASC
            """;
        return jdbc.query(sql, orderRowMapper());
    }

    public Optional<Order> findById(String orderId) {
        return jdbc.query("SELECT * FROM orders WHERE id = ?", orderRowMapper(), orderId)
            .stream().findFirst();
    }

    public int countBySymbolAndStatus(String symbol, String status) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE symbol = ? AND status = ?",
            Integer.class, symbol, status);
        return count == null ? 0 : count;
    }

    private RowMapper<Order> orderRowMapper() {
        return (ResultSet rs, int rowNum) -> Order.builder()
            .id(rs.getString("id"))
            .symbol(rs.getString("symbol"))
            .side(Side.valueOf(rs.getString("side")))
            .orderType(OrderType.valueOf(rs.getString("order_type")))
            .price(rs.getBigDecimal("price"))
            .originalQuantity(rs.getLong("original_quantity"))
            .remainingQuantity(rs.getLong("remaining_quantity"))
            .status(OrderStatus.valueOf(rs.getString("status")))
            .clientOrderId(rs.getString("client_order_id"))
            .createdAt(rs.getTimestamp("created_at").toInstant())
            .build();
    }
}
