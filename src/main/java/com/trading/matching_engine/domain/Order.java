package com.trading.matching_engine.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A live order. Everything except quantity and status is immutable; the two mutable
 * fields are written by exactly one thread (MatchingEngineWorker) and are never read
 * off it — {@link #snapshot()} is the only way state leaves the matching thread.
 *
 * priceTicks is the field the book actually navigates on; price is kept for the API
 * and database boundary only.
 */
public final class Order {
    private final String id;
    private final String symbol;
    private final Side side;
    private final OrderType orderType;
    private final BigDecimal price;   // null for MARKET
    private final long priceTicks;    // 0 for MARKET — never read on the market path
    private final long originalQuantity;
    private final String clientOrderId;
    private final Instant createdAt;

    // matching-thread-only mutable state
    private long remainingQuantity;
    private OrderStatus status;

    private Order(Builder b) {
        this.id = b.id;
        this.symbol = b.symbol;
        this.side = b.side;
        this.orderType = b.orderType;
        this.price = b.price;
        this.priceTicks = b.price == null ? 0L : Ticks.fromDecimal(b.price);
        this.originalQuantity = b.originalQuantity;
        this.clientOrderId = b.clientOrderId;
        this.createdAt = b.createdAt;
        this.remainingQuantity = b.remainingQuantity;
        this.status = b.status == null ? OrderStatus.NEW : b.status;
    }

    public OrderSnapshot snapshot() {
        return new OrderSnapshot(id, symbol, side, orderType, price,
            originalQuantity, remainingQuantity, status, clientOrderId, createdAt);
    }

    public String getId() { return id; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getOrderType() { return orderType; }
    public BigDecimal getPrice() { return price; }
    public long getPriceTicks() { return priceTicks; }
    public long getOriginalQuantity() { return originalQuantity; }
    public String getClientOrderId() { return clientOrderId; }
    public Instant getCreatedAt() { return createdAt; }
    public long getRemainingQuantity() { return remainingQuantity; }
    public OrderStatus getStatus() { return status; }

    public void setRemainingQuantity(long remainingQuantity) { this.remainingQuantity = remainingQuantity; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public boolean isMarket() { return orderType == OrderType.MARKET; }

    @Override
    public String toString() {
        return "Order[" + id + " " + symbol + " " + side + " " + orderType
            + " px=" + price + " rem=" + remainingQuantity + "/" + originalQuantity
            + " " + status + "]";
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private String symbol;
        private Side side;
        private OrderType orderType;
        private BigDecimal price;
        private long originalQuantity;
        private long remainingQuantity = -1;
        private OrderStatus status = OrderStatus.NEW;
        private String clientOrderId;
        private Instant createdAt;

        public Builder id(String v) { this.id = v; return this; }
        public Builder symbol(String v) { this.symbol = v; return this; }
        public Builder side(Side v) { this.side = v; return this; }
        public Builder orderType(OrderType v) { this.orderType = v; return this; }
        public Builder price(BigDecimal v) { this.price = v; return this; }
        public Builder originalQuantity(long v) { this.originalQuantity = v; return this; }
        public Builder remainingQuantity(long v) { this.remainingQuantity = v; return this; }
        public Builder status(OrderStatus v) { this.status = v; return this; }
        public Builder clientOrderId(String v) { this.clientOrderId = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }

        public Order build() {
            if (remainingQuantity < 0) remainingQuantity = originalQuantity;
            if (createdAt == null) createdAt = Instant.now();
            return new Order(this);
        }
    }
}
