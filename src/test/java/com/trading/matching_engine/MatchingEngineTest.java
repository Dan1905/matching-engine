package com.trading.matching_engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.matching.MatchResult;
import com.trading.matching_engine.matching.MatchingEngine;

class MatchingEngineTest {

    private Order makeOrder(Side side, OrderType type, String price, long qty) {
        return Order.builder()
            .id(UUID.randomUUID().toString())
            .symbol("AAPL")
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

    @Test
    void exactMatch_producesOneTrade() {
        MatchingEngine engine = new MatchingEngine();
        Order buy  = makeOrder(Side.BUY,  OrderType.LIMIT, "100.00", 100);
        Order sell = makeOrder(Side.SELL, OrderType.LIMIT, "100.00", 100);
        engine.processOrder(buy);
        MatchResult result = engine.processOrder(sell);

        assertEquals(1, result.getTrades().size());
        assertEquals(100, result.getTrades().get(0).getExecutedQty());
        assertEquals(2, result.getUpdatedOrders().size());
        assertTrue(result.getUpdatedOrders().contains(buy));
        assertTrue(result.getUpdatedOrders().contains(sell));
    }

    @Test
    void partialFill_leavesQtyCorrect() {
        MatchingEngine engine = new MatchingEngine();
        Order buy  = makeOrder(Side.BUY,  OrderType.LIMIT, "100.00", 200);
        Order sell = makeOrder(Side.SELL, OrderType.LIMIT, "100.00", 100);
        engine.processOrder(buy);
        engine.processOrder(sell);

        assertEquals(100, buy.getRemainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
    }

    @Test
    void noMatch_whenPriceDoesNotCross() {
        MatchingEngine engine = new MatchingEngine();
        Order buy  = makeOrder(Side.BUY,  OrderType.LIMIT, "99.00", 100);
        Order sell = makeOrder(Side.SELL, OrderType.LIMIT, "101.00", 100);
        engine.processOrder(buy);
        MatchResult result = engine.processOrder(sell);

        assertTrue(result.getTrades().isEmpty());
    }

    @Test
    void priceTimePriority_earlierOrderFillsFirst() {
        MatchingEngine engine = new MatchingEngine();
        Order sell1 = makeOrder(Side.SELL, OrderType.LIMIT, "100.00", 100);
        Order sell2 = makeOrder(Side.SELL, OrderType.LIMIT, "100.00", 100);
        engine.processOrder(sell1);
        engine.processOrder(sell2);

        Order buy = makeOrder(Side.BUY, OrderType.LIMIT, "100.00", 100);
        engine.processOrder(buy);

        assertEquals(OrderStatus.FILLED, sell1.getStatus());
        assertEquals(OrderStatus.NEW, sell2.getStatus());
    }

    @Test
    void marketOrder_fillsAtBestAvailablePrice() {
        MatchingEngine engine = new MatchingEngine();
        Order sell = makeOrder(Side.SELL, OrderType.LIMIT, "95.00", 100);
        engine.processOrder(sell);

        Order marketBuy = makeOrder(Side.BUY, OrderType.MARKET, null, 100);
        MatchResult result = engine.processOrder(marketBuy);

        assertEquals(1, result.getTrades().size());
        assertEquals(new BigDecimal("95.00"), result.getTrades().get(0).getExecutedPrice());
        assertEquals(OrderStatus.FILLED, marketBuy.getStatus());
    }

    @Test
    void marketOrder_doesNotRestInBook_whenUnfilled() {
        MatchingEngine engine = new MatchingEngine();
        Order marketBuy = makeOrder(Side.BUY, OrderType.MARKET, null, 100);
        MatchResult result = engine.processOrder(marketBuy);

        assertTrue(result.getTrades().isEmpty());
        assertTrue(engine.getBestBid().isEmpty(), "Market order must never rest in book");
    }
}
