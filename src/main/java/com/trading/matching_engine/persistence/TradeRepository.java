package com.trading.matching_engine.persistence;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.trading.matching_engine.domain.Trade;

@Repository
public class TradeRepository {
    private final JdbcTemplate jdbc;

    public TradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void batchInsert(List<Trade> trades) {
        String sql = """
            INSERT INTO trades
              (id, buy_order_id, sell_order_id, symbol,
               executed_price, executed_qty, executed_at)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (id) DO NOTHING
            """;

        jdbc.batchUpdate(sql, trades, trades.size(), (ps, t) -> {
            ps.setString(1, t.getId());
            ps.setString(2, t.getBuyOrderId());
            ps.setString(3, t.getSellOrderId());
            ps.setString(4, t.getSymbol());
            ps.setBigDecimal(5, t.getExecutedPrice());
            ps.setLong(6, t.getExecutedQty());
            ps.setTimestamp(7, Timestamp.from(t.getExecutedAt()));
        });
    }

    public List<Trade> findBySymbol(String symbol) {
        String sql = "SELECT * FROM trades WHERE symbol = ? ORDER BY executed_at DESC";
        return jdbc.query(sql, tradeRowMapper(), symbol);
    }

    private RowMapper<Trade> tradeRowMapper() {
        return (ResultSet rs, int rowNum) -> Trade.builder()
            .id(rs.getString("id"))
            .buyOrderId(rs.getString("buy_order_id"))
            .sellOrderId(rs.getString("sell_order_id"))
            .symbol(rs.getString("symbol"))
            .executedPrice(rs.getBigDecimal("executed_price"))
            .executedQty(rs.getLong("executed_qty"))
            .executedAt(rs.getTimestamp("executed_at").toInstant())
            .build();
    }
}
