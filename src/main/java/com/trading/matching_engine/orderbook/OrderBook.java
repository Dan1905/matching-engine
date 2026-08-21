package com.trading.matching_engine.orderbook;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.domain.Ticks;
import com.trading.matching_engine.domain.Trade;

/**
 * The book for ONE symbol. Price-time priority: a TreeMap of price levels keyed by
 * integer ticks (bids descending, asks ascending), each level a FIFO of resting orders.
 *
 * Single-writer: every method here must be called from the matching thread only.
 */
public final class OrderBook {
    private final String symbol;
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();

    /** Shared with the engine so cancel-by-id can route to the right book in O(1). */
    private final Map<String, OrderNode> index;
    private final TradeIdGenerator tradeIds;

    public OrderBook(String symbol, Map<String, OrderNode> index, TradeIdGenerator tradeIds) {
        this.symbol = symbol;
        this.index = index;
        this.tradeIds = tradeIds;
    }

    /**
     * Matches an incoming order against the resting side, appending any fills to
     * {@code trades} and every touched resting order to {@code updatedOrders}.
     */
    public void match(Order incoming, Instant now, List<Trade> trades, List<Order> updatedOrders) {
        boolean buying = incoming.getSide() == Side.BUY;
        TreeMap<Long, PriceLevel> resting = buying ? asks : bids;

        while (incoming.getRemainingQuantity() > 0 && !resting.isEmpty()) {
            PriceLevel best = resting.firstEntry().getValue();
            if (!crosses(incoming, best.getPriceTicks(), buying)) break;

            Order maker = best.peek();
            long fillQty = Math.min(incoming.getRemainingQuantity(), maker.getRemainingQuantity());

            trades.add(Trade.builder()
                .id(tradeIds.next())
                .symbol(symbol)
                .buyOrderId(buying ? incoming.getId() : maker.getId())
                .sellOrderId(buying ? maker.getId() : incoming.getId())
                .executedPrice(Ticks.toDecimal(best.getPriceTicks()))
                .executedQty(fillQty)
                .executedAt(now)
                .build());

            incoming.setRemainingQuantity(incoming.getRemainingQuantity() - fillQty);
            maker.setRemainingQuantity(maker.getRemainingQuantity() - fillQty);
            best.reduceQuantity(fillQty);
            applyFillStatus(maker);

            // A maker can be touched at most once per incoming order: if it survives the
            // fill the incoming order is exhausted and the loop ends. No dedupe needed.
            updatedOrders.add(maker);

            // remove() drops the level from the side map once it empties. Polling the
            // map here as well would discard the NEXT level and silently skip it.
            if (maker.getRemainingQuantity() == 0) {
                remove(maker.getId());
            }
        }
    }

    private boolean crosses(Order incoming, long restingTicks, boolean buying) {
        if (incoming.isMarket()) return true;
        return buying ? incoming.getPriceTicks() >= restingTicks
                      : incoming.getPriceTicks() <= restingTicks;
    }

    /** Rests the unfilled remainder of a limit order. */
    public void rest(Order order) {
        TreeMap<Long, PriceLevel> side = order.getSide() == Side.BUY ? bids : asks;
        PriceLevel level = side.computeIfAbsent(order.getPriceTicks(), PriceLevel::new);
        OrderNode node = level.addLast(order);
        node.book = this;
        index.put(order.getId(), node);
    }

    /** O(1) removal by id — no side, no price, no scan. */
    public Optional<Order> remove(String orderId) {
        OrderNode node = index.remove(orderId);
        if (node == null || !node.isLinked()) return Optional.empty();

        PriceLevel level = node.level;
        Order order = node.order;
        level.unlink(node);
        if (level.isEmpty()) {
            TreeMap<Long, PriceLevel> side = order.getSide() == Side.BUY ? bids : asks;
            side.remove(level.getPriceTicks());
        }
        node.book = null;
        return Optional.of(order);
    }

    private void applyFillStatus(Order order) {
        if (order.getRemainingQuantity() == 0) {
            order.setStatus(OrderStatus.FILLED);
        } else if (order.getRemainingQuantity() < order.getOriginalQuantity()) {
            order.setStatus(OrderStatus.PARTIALLY_FILLED);
        }
    }

    public Optional<Order> getBestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.ofNullable(bids.firstEntry().getValue().peek());
    }

    public Optional<Order> getBestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.ofNullable(asks.firstEntry().getValue().peek());
    }

    /** True when the best bid is at or above the best ask — should never happen. */
    public boolean isCrossed() {
        if (bids.isEmpty() || asks.isEmpty()) return false;
        return bids.firstKey() >= asks.firstKey();
    }

    public String getSymbol() { return symbol; }
    public boolean isEmpty() { return bids.isEmpty() && asks.isEmpty(); }
    public int getRestingOrderCount() {
        int n = 0;
        for (PriceLevel l : bids.values()) n += l.getOrderCount();
        for (PriceLevel l : asks.values()) n += l.getOrderCount();
        return n;
    }
}
