package com.trading.matching_engine.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.Side;

@Repository
public class OrderRepository {
     private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void batchInsert(List<Order> orders) {
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

        jdbc.batchUpdate(sql, orders, orders.size(), (ps, o) -> {
            ps.setString(1, o.getId());
            ps.setString(2, o.getSymbol());
            ps.setString(3, o.getSide().name());
            ps.setString(4, o.getOrderType().name());
            ps.setBigDecimal(5, o.getPrice());
            ps.setLong(6, o.getOriginalQuantity());
            ps.setLong(7, o.getRemainingQuantity());
            ps.setString(8, o.getStatus().name());
            ps.setString(9, o.getClientOrderId());
            ps.setTimestamp(10, Timestamp.from(o.getCreatedAt()));
            ps.setTimestamp(11, Timestamp.from(Instant.now()));
        });
    }

    public int countBySymbolAndStatus(String symbol, String status) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE symbol = ? AND status = ?",
            Integer.class,
            symbol,
            status);
        return count == null ? 0 : count;
    }

    public java.util.Optional<Order> findById(String orderId) {
        String sql = "SELECT * FROM orders WHERE id = ?";
        List<Order> results = jdbc.query(sql, orderRowMapper(), orderId);
        return results.stream().findFirst();
    }

    private RowMapper<Order> orderRowMapper() {
        return (java.sql.ResultSet rs, int rowNum) -> Order.builder()
            .id(rs.getString("id"))
            .symbol(rs.getString("symbol"))
            .side(Side.valueOf(rs.getString("side")))
            .orderType(com.trading.matching_engine.domain.OrderType.valueOf(rs.getString("order_type")))
            .price(rs.getBigDecimal("price"))
            .originalQuantity(rs.getLong("original_quantity"))
            .remainingQuantity(rs.getLong("remaining_quantity"))
            .status(OrderStatus.valueOf(rs.getString("status")))
            .clientOrderId(rs.getString("client_order_id"))
            .createdAt(rs.getTimestamp("created_at").toInstant())
            .build();
    }
}
