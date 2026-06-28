package com.trading.matching_engine.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.OrderIngress;

/**
 * Pure order generator/producer. Generates synthetic buy/sell orders and submits them
 * to the ingress queue. Contains NO timing or measurement logic — that lives in
 * LatencyTestRunner instead, which is the actual benchmarking entry point.
 */
public class OrderGenerator {

    private final OrderIngress ingress;
    private final Random random = new Random();
    private static final BigDecimal MID = new BigDecimal("150.00");
    private static final String[] SYMBOLS = {"AAPL", "MSFT", "GOOGL"};

    public OrderGenerator(OrderIngress ingress) {
        this.ingress = ingress;
    }

    /** Generates and submits a single random order. Returns the order (caller may need its id). */
    public Order submitRandomOrder(int seed) {
        Order order = randomOrder(seed);
        ingress.submit(new EngineCommand.SubmitOrder(order));
        return order;
    }

    /** Submits `count` random orders sequentially from the calling thread. */
    public void submitBatch(int count) {
        for (int i = 0; i < count; i++) {
            submitRandomOrder(i);
        }
    }

    /** Submits `ordersPerThread` orders each from `producerThreads` concurrent threads. */
    public void submitConcurrent(int producerThreads, int ordersPerThread) throws InterruptedException {
        Thread[] threads = new Thread[producerThreads];

        for (int t = 0; t < producerThreads; t++) {
            final int threadId = t;
            threads[t] = Thread.ofPlatform().start(() -> {
                for (int i = 0; i < ordersPerThread; i++) {
                    submitRandomOrder(threadId * ordersPerThread + i);
                }
            });
        }

        for (Thread t : threads) t.join();
    }

    public Order randomOrder(int i) {
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        boolean isMarket = random.nextInt(10) == 0; // 10% market orders
        BigDecimal offset = BigDecimal.valueOf(random.nextInt(5));
        BigDecimal price = side == Side.BUY ? MID.subtract(offset) : MID.add(offset);
        long qty = 100 + random.nextInt(900);

        return Order.builder()
            .id(UUID.randomUUID().toString())
            .symbol(SYMBOLS[random.nextInt(SYMBOLS.length)])
            .side(side)
            .orderType(isMarket ? OrderType.MARKET : OrderType.LIMIT)
            .price(isMarket ? null : price)
            .originalQuantity(qty)
            .remainingQuantity(qty)
            .status(OrderStatus.NEW)
            .clientOrderId("SIM-" + i)
            .createdAt(Instant.now())
            .build();
    }
}