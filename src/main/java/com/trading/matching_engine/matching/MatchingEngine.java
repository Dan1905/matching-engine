package com.trading.matching_engine.matching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Trade;
import com.trading.matching_engine.orderbook.OrderBook;
import com.trading.matching_engine.orderbook.OrderNode;
import com.trading.matching_engine.orderbook.TradeIdGenerator;

/**
 * Routes each order to the book for its symbol and owns the id index that makes
 * cancel-by-id O(1) across every book.
 *
 * Called by exactly ONE thread — MatchingEngineWorker. Every field here is plain,
 * non-volatile, unsynchronized state, and that is the point: correctness comes from
 * the threading model, not from runtime locking.
 */
@Component
public class MatchingEngine {
    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    private final Map<String, OrderBook> books = new HashMap<>();
    private final Map<String, OrderNode> index = new HashMap<>();
    private final TradeIdGenerator tradeIds = new TradeIdGenerator();

    public MatchResult processOrder(Order incoming) {
        Instant now = Instant.now();          // once per order, not once per trade
        OrderBook book = bookFor(incoming.getSymbol());

        List<Trade> trades = new ArrayList<>(2);
        List<Order> updatedOrders = new ArrayList<>(2);

        book.match(incoming, now, trades, updatedOrders);

        if (incoming.getRemainingQuantity() > 0) {
            if (incoming.getOrderType() == OrderType.LIMIT) {
                book.rest(incoming);
            } else {
                // Market orders are immediate-or-cancel: the remainder never rests. Give
                // it a terminal status so the client is told, instead of it vanishing.
                incoming.setStatus(trades.isEmpty() ? OrderStatus.REJECTED : OrderStatus.CANCELLED);
                updatedOrders.add(incoming);
                return new MatchResult(incoming, trades, updatedOrders);
            }
        }

        applyStatus(incoming);
        updatedOrders.add(incoming);
        return new MatchResult(incoming, trades, updatedOrders);
    }

    /**
     * Places a recovered order straight into the book without matching. A consistent
     * book never holds crossing orders, so replaying them through processOrder would
     * invent trades that never happened.
     */
    public void restore(Order order) {
        if (order.getOrderType() != OrderType.LIMIT || order.getPrice() == null) return;
        if (order.getRemainingQuantity() <= 0) return;
        bookFor(order.getSymbol()).rest(order);
    }

    /** Cancels by id alone — the caller does not need to know the side or price. */
    public Optional<Order> cancel(String orderId) {
        OrderNode node = index.get(orderId);
        if (node == null || node.book() == null) return Optional.empty();

        Optional<Order> cancelled = node.book().remove(orderId);
        cancelled.ifPresent(o -> o.setStatus(OrderStatus.CANCELLED));
        return cancelled;
    }

    private OrderBook bookFor(String symbol) {
        return books.computeIfAbsent(symbol, s -> new OrderBook(s, index, tradeIds));
    }

    private void applyStatus(Order order) {
        if (order.getRemainingQuantity() == 0) {
            order.setStatus(OrderStatus.FILLED);
        } else if (order.getRemainingQuantity() < order.getOriginalQuantity()) {
            order.setStatus(OrderStatus.PARTIALLY_FILLED);
        } else {
            order.setStatus(OrderStatus.NEW);
        }
    }

    public Optional<Order> getBestBid(String symbol) {
        OrderBook book = books.get(symbol);
        return book == null ? Optional.empty() : book.getBestBid();
    }

    public Optional<Order> getBestAsk(String symbol) {
        OrderBook book = books.get(symbol);
        return book == null ? Optional.empty() : book.getBestAsk();
    }

    public int getRestingOrderCount() {
        return index.size();
    }

    /** Post-recovery sanity check: a restored book must not be crossed. */
    public void verifyUncrossed() {
        books.values().stream().filter(OrderBook::isCrossed).forEach(b ->
            log.error("Recovered book for {} is CROSSED — best bid >= best ask. "
                + "The persisted order state is inconsistent.", b.getSymbol()));
    }
}
