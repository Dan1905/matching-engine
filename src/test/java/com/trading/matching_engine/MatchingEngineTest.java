package com.trading.matching_engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderSnapshot;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.matching.MatchResult;
import com.trading.matching_engine.matching.MatchingEngine;

class MatchingEngineTest {

    private Order order(String symbol, Side side, OrderType type, String price, long qty) {
        return Order.builder()
            .id(UUID.randomUUID().toString())
            .symbol(symbol)
            .side(side)
            .orderType(type)
            .price(price == null ? null : new BigDecimal(price))
            .originalQuantity(qty)
            .remainingQuantity(qty)
            .status(OrderStatus.NEW)
            .clientOrderId(UUID.randomUUID().toString())
            .createdAt(Instant.now())
            .build();
    }

    private Order limit(Side side, String price, long qty) {
        return order("AAPL", side, OrderType.LIMIT, price, qty);
    }

    @Test
    void exactMatch_producesOneTrade() {
        MatchingEngine engine = new MatchingEngine();
        Order buy = limit(Side.BUY, "100.00", 100);
        Order sell = limit(Side.SELL, "100.00", 100);
        engine.processOrder(buy);
        MatchResult result = engine.processOrder(sell);

        assertEquals(1, result.trades().size());
        assertEquals(100, result.trades().get(0).getExecutedQty());
        assertEquals(2, result.updatedOrders().size());
        assertTrue(result.updatedOrders().contains(buy));
        assertTrue(result.updatedOrders().contains(sell));
    }

    @Test
    void partialFill_leavesQtyCorrect() {
        MatchingEngine engine = new MatchingEngine();
        Order buy = limit(Side.BUY, "100.00", 200);
        engine.processOrder(buy);
        engine.processOrder(limit(Side.SELL, "100.00", 100));

        assertEquals(100, buy.getRemainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
    }

    @Test
    void noMatch_whenPriceDoesNotCross() {
        MatchingEngine engine = new MatchingEngine();
        engine.processOrder(limit(Side.BUY, "99.00", 100));
        MatchResult result = engine.processOrder(limit(Side.SELL, "101.00", 100));

        assertTrue(result.trades().isEmpty());
    }

    @Test
    void priceTimePriority_earlierOrderFillsFirst() {
        MatchingEngine engine = new MatchingEngine();
        Order sell1 = limit(Side.SELL, "100.00", 100);
        Order sell2 = limit(Side.SELL, "100.00", 100);
        engine.processOrder(sell1);
        engine.processOrder(sell2);
        engine.processOrder(limit(Side.BUY, "100.00", 100));

        assertEquals(OrderStatus.FILLED, sell1.getStatus());
        assertEquals(OrderStatus.NEW, sell2.getStatus());
    }

    @Test
    void aggressiveOrder_sweepsMultiplePriceLevels_bestFirst() {
        MatchingEngine engine = new MatchingEngine();
        engine.processOrder(limit(Side.SELL, "101.00", 100));
        engine.processOrder(limit(Side.SELL, "100.00", 100));
        engine.processOrder(limit(Side.SELL, "102.00", 100));

        MatchResult result = engine.processOrder(limit(Side.BUY, "102.00", 300));

        assertEquals(3, result.trades().size());
        assertEquals(new BigDecimal("100.0000"), result.trades().get(0).getExecutedPrice());
        assertEquals(new BigDecimal("101.0000"), result.trades().get(1).getExecutedPrice());
        assertEquals(new BigDecimal("102.0000"), result.trades().get(2).getExecutedPrice());
        assertEquals(0, engine.getRestingOrderCount());
    }

    // ---- per-symbol isolation ------------------------------------------------

    @Test
    void ordersNeverMatchAcrossSymbols() {
        MatchingEngine engine = new MatchingEngine();
        Order appleBuy = order("AAPL", Side.BUY, OrderType.LIMIT, "100.00", 100);
        engine.processOrder(appleBuy);

        MatchResult result = engine.processOrder(
            order("TSLA", Side.SELL, OrderType.LIMIT, "100.00", 100));

        assertTrue(result.trades().isEmpty(), "A TSLA sell must not fill against an AAPL bid");
        assertEquals(OrderStatus.NEW, appleBuy.getStatus());
        assertTrue(engine.getBestBid("TSLA").isEmpty());
        assertTrue(engine.getBestAsk("AAPL").isEmpty());
        assertEquals(2, engine.getRestingOrderCount());
    }

    @Test
    void eachSymbolKeepsItsOwnBestPrices() {
        MatchingEngine engine = new MatchingEngine();
        engine.processOrder(order("AAPL", Side.BUY, OrderType.LIMIT, "100.00", 10));
        engine.processOrder(order("MSFT", Side.BUY, OrderType.LIMIT, "420.00", 10));

        assertEquals(new BigDecimal("100.00"), engine.getBestBid("AAPL").orElseThrow().getPrice());
        assertEquals(new BigDecimal("420.00"), engine.getBestBid("MSFT").orElseThrow().getPrice());
    }

    // ---- market order semantics ---------------------------------------------

    @Test
    void marketOrder_fillsAtBestAvailablePrice() {
        MatchingEngine engine = new MatchingEngine();
        engine.processOrder(limit(Side.SELL, "95.00", 100));

        Order marketBuy = order("AAPL", Side.BUY, OrderType.MARKET, null, 100);
        MatchResult result = engine.processOrder(marketBuy);

        assertEquals(1, result.trades().size());
        assertEquals(new BigDecimal("95.0000"), result.trades().get(0).getExecutedPrice());
        assertEquals(OrderStatus.FILLED, marketBuy.getStatus());
    }

    @Test
    void marketOrder_withNoLiquidity_isRejectedNotSilentlyDropped() {
        MatchingEngine engine = new MatchingEngine();
        Order marketBuy = order("AAPL", Side.BUY, OrderType.MARKET, null, 100);
        MatchResult result = engine.processOrder(marketBuy);

        assertTrue(result.trades().isEmpty());
        assertEquals(OrderStatus.REJECTED, marketBuy.getStatus(),
            "An unfillable market order must reach a terminal status the client can see");
        assertTrue(result.updatedOrders().contains(marketBuy),
            "The rejection must be published so it reaches the database and cache");
        assertTrue(engine.getBestBid("AAPL").isEmpty(), "Market order must never rest in the book");
    }

    @Test
    void marketOrder_partiallyFilled_remainderIsCancelled() {
        MatchingEngine engine = new MatchingEngine();
        engine.processOrder(limit(Side.SELL, "95.00", 40));

        Order marketBuy = order("AAPL", Side.BUY, OrderType.MARKET, null, 100);
        engine.processOrder(marketBuy);

        assertEquals(60, marketBuy.getRemainingQuantity());
        assertEquals(OrderStatus.CANCELLED, marketBuy.getStatus());
        assertTrue(engine.getBestBid("AAPL").isEmpty());
    }

    // ---- cancel --------------------------------------------------------------

    @Test
    void cancelById_needsNoSideOrPrice() {
        MatchingEngine engine = new MatchingEngine();
        Order resting = limit(Side.BUY, "100.00", 100);
        engine.processOrder(resting);

        assertTrue(engine.cancel(resting.getId()).isPresent());
        assertEquals(OrderStatus.CANCELLED, resting.getStatus());
        assertTrue(engine.getBestBid("AAPL").isEmpty());
        assertEquals(0, engine.getRestingOrderCount());
    }

    @Test
    void cancel_removesOnlyTheTargetOrderAtThatPrice() {
        MatchingEngine engine = new MatchingEngine();
        Order first = limit(Side.BUY, "100.00", 100);
        Order second = limit(Side.BUY, "100.00", 100);
        Order third = limit(Side.BUY, "100.00", 100);
        engine.processOrder(first);
        engine.processOrder(second);
        engine.processOrder(third);

        engine.cancel(second.getId());

        assertEquals(2, engine.getRestingOrderCount());
        // time priority is preserved either side of the hole
        engine.processOrder(limit(Side.SELL, "100.00", 200));
        assertEquals(OrderStatus.FILLED, first.getStatus());
        assertEquals(OrderStatus.CANCELLED, second.getStatus());
        assertEquals(OrderStatus.FILLED, third.getStatus());
    }

    @Test
    void cancel_unknownOrFilledOrder_isANoOp() {
        MatchingEngine engine = new MatchingEngine();
        assertTrue(engine.cancel("does-not-exist").isEmpty());

        Order buy = limit(Side.BUY, "100.00", 100);
        engine.processOrder(buy);
        engine.processOrder(limit(Side.SELL, "100.00", 100));

        assertTrue(engine.cancel(buy.getId()).isEmpty(), "A filled order is no longer cancellable");
        assertEquals(OrderStatus.FILLED, buy.getStatus());
    }

    @Test
    void cancelledOrderId_isReleasedFromTheIndex() {
        MatchingEngine engine = new MatchingEngine();
        Order resting = limit(Side.BUY, "100.00", 100);
        engine.processOrder(resting);
        engine.cancel(resting.getId());

        assertEquals(0, engine.getRestingOrderCount());
        assertTrue(engine.cancel(resting.getId()).isEmpty(), "Cancelling twice must not resurrect it");
    }

    // ---- snapshots -----------------------------------------------------------

    @Test
    void snapshot_doesNotSeeLaterMutationsOfTheOrder() {
        MatchingEngine engine = new MatchingEngine();
        Order resting = limit(Side.SELL, "100.00", 100);
        engine.processOrder(resting);

        engine.processOrder(limit(Side.BUY, "100.00", 40));
        OrderSnapshot afterFirstFill = resting.snapshot();

        engine.processOrder(limit(Side.BUY, "100.00", 60));

        assertEquals(60, afterFirstFill.remainingQuantity(),
            "The snapshot must record the state at the moment it was taken");
        assertEquals(OrderStatus.PARTIALLY_FILLED, afterFirstFill.status());
        assertEquals(0, resting.getRemainingQuantity());
        assertEquals(OrderStatus.FILLED, resting.getStatus());
    }

    // ---- recovery ------------------------------------------------------------

    @Test
    void restore_rebuildsTheBookWithoutInventingTrades() {
        MatchingEngine engine = new MatchingEngine();
        Order bid = limit(Side.BUY, "99.00", 100);
        Order ask = limit(Side.SELL, "101.00", 100);

        engine.restore(bid);
        engine.restore(ask);

        assertEquals(2, engine.getRestingOrderCount());
        assertEquals(bid, engine.getBestBid("AAPL").orElseThrow());
        assertEquals(ask, engine.getBestAsk("AAPL").orElseThrow());

        // a restored order still matches and still cancels
        MatchResult result = engine.processOrder(limit(Side.SELL, "99.00", 100));
        assertEquals(1, result.trades().size());
        assertEquals(OrderStatus.FILLED, bid.getStatus());
        assertTrue(engine.cancel(ask.getId()).isPresent());
    }

    @Test
    void restore_ignoresMarketAndFullyFilledOrders() {
        MatchingEngine engine = new MatchingEngine();
        engine.restore(order("AAPL", Side.BUY, OrderType.MARKET, null, 100));
        engine.restore(limit(Side.BUY, "100.00", 0));

        assertEquals(0, engine.getRestingOrderCount());
        assertFalse(engine.getBestBid("AAPL").isPresent());
    }
}
