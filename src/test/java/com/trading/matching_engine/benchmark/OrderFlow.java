package com.trading.matching_engine.benchmark;

import java.math.BigDecimal;
import java.time.Instant;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;

/**
 * Shared order stream for every benchmark, so the lock-based and single-writer runs are
 * measured on identical input.
 *
 * Two properties matter:
 *  - Deterministic, thread-local generation (xorshift, no Math.random) — a shared
 *    synchronized RNG inside the timed loop measures the RNG, not the book.
 *  - Sides alternate across a narrow price band, so most orders cross and the book stays
 *    bounded. A book that only grows turns the benchmark into a GC measurement.
 */
final class OrderFlow {
    private static final long BASE_TICKS_PRICE = 15000; // 150.00 at scale 2
    private static final int PRICE_LEVELS = 20;
    private static final String[] SYMBOLS = {"AAPL", "MSFT", "GOOGL", "AMZN"};

    private long state;
    private long counter;

    OrderFlow(long seed) {
        this.state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
    }

    private long nextLong() {
        state ^= state << 13;
        state ^= state >>> 7;
        state ^= state << 17;
        return state;
    }

    private int nextInt(int bound) {
        return (int) Math.floorMod(nextLong(), bound);
    }

    Order next() {
        long n = counter++;
        Side side = (n & 1) == 0 ? Side.BUY : Side.SELL;
        long priceCents = BASE_TICKS_PRICE + nextInt(PRICE_LEVELS) - (PRICE_LEVELS / 2);
        long qty = 100;

        return Order.builder()
            .id(Long.toString(n) + '-' + Integer.toHexString(System.identityHashCode(this)))
            .symbol(SYMBOLS[nextInt(SYMBOLS.length)])
            .side(side)
            .orderType(OrderType.LIMIT)
            .price(BigDecimal.valueOf(priceCents, 2))
            .originalQuantity(qty)
            .remainingQuantity(qty)
            .status(OrderStatus.NEW)
            .clientOrderId("BENCH")
            .createdAt(Instant.EPOCH)
            .build();
    }
}
