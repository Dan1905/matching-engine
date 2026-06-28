CREATE TABLE orders (
    id                 VARCHAR(36) PRIMARY KEY,
    symbol             VARCHAR(20)   NOT NULL,
    side               VARCHAR(4)    NOT NULL,
    order_type         VARCHAR(10)   NOT NULL,
    price              NUMERIC(18,4),
    original_quantity  BIGINT        NOT NULL,
    remaining_quantity BIGINT        NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    client_order_id    VARCHAR(50)   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE trades (
    id              VARCHAR(36) PRIMARY KEY,
    buy_order_id    VARCHAR(36)   NOT NULL REFERENCES orders(id),
    sell_order_id   VARCHAR(36)   NOT NULL REFERENCES orders(id),
    symbol          VARCHAR(20)   NOT NULL,
    executed_price  NUMERIC(18,4) NOT NULL,
    executed_qty    BIGINT        NOT NULL,
    executed_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE market_data (
    symbol         VARCHAR(20) PRIMARY KEY,
    bid            NUMERIC(18,4),
    ask            NUMERIC(18,4),
    last_sale      NUMERIC(18,4),
    closing_price  NUMERIC(18,4),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trades_symbol      ON trades(symbol);
CREATE INDEX idx_trades_executed_at ON trades(executed_at);
CREATE INDEX idx_orders_symbol      ON orders(symbol);
CREATE INDEX idx_orders_status      ON orders(status);