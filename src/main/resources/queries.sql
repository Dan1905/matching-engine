-- Realized P&L per symbol
SELECT
    symbol,
    SUM(CASE WHEN side = 'SELL' THEN  executed_price * executed_qty
             WHEN side = 'BUY'  THEN -executed_price * executed_qty END) AS realized_pnl,
    SUM(CASE WHEN side = 'BUY'  THEN  executed_qty
             WHEN side = 'SELL' THEN -executed_qty END)                  AS net_position
FROM trades t
JOIN orders o ON o.id = t.buy_order_id OR o.id = t.sell_order_id
GROUP BY symbol;

-- Turnover per symbol
SELECT symbol,
    SUM(executed_price * executed_qty) AS turnover,
    SUM(executed_qty)                  AS total_volume,
    COUNT(*)                           AS trade_count
FROM trades
GROUP BY symbol
ORDER BY turnover DESC;

-- VWAP per symbol
SELECT symbol,
    SUM(executed_price * executed_qty) / SUM(executed_qty) AS vwap,
    SUM(executed_qty) AS total_volume
FROM trades
GROUP BY symbol;

-- Today's trades
SELECT * FROM trades
WHERE DATE(executed_at) = CURRENT_DATE
ORDER BY executed_at DESC;

-- Open orders resting in book
SELECT symbol, side, price,
    SUM(remaining_quantity) AS total_resting_qty,
    COUNT(*) AS order_count
FROM orders
WHERE status IN ('NEW', 'PARTIALLY_FILLED')
GROUP BY symbol, side, price
ORDER BY symbol, side, price DESC;

-- Fill rate per symbol
SELECT symbol,
    COUNT(*) AS total_orders,
    SUM(CASE WHEN status = 'FILLED' THEN 1 ELSE 0 END) AS filled,
    SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled,
    ROUND(100.0 * SUM(CASE WHEN status = 'FILLED' THEN 1 ELSE 0 END) / COUNT(*), 2) AS fill_rate_pct
FROM orders
GROUP BY symbol;