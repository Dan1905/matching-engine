package com.trading.matching_engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.OrderIngress;
import com.trading.matching_engine.engine.QueueBasedIngress;
import com.trading.matching_engine.matching.MatchingEngine;

class OrderIngressTest {

    @Test
    void manyProducerThreads_noOrderLostOrDuplicated() throws InterruptedException {
        OrderIngress ingress = new QueueBasedIngress();
        MatchingEngine engine = new MatchingEngine();
        AtomicInteger processedCount = new AtomicInteger();

        // single worker thread — exactly like MatchingEngineWorker, simplified for this test
        Thread worker = Thread.ofPlatform().start(() -> {
            while (true) {
                try {
                    EngineCommand cmd = ingress.take();
                    if (cmd instanceof EngineCommand.SubmitOrder submit) {
                        engine.processOrder(submit.order());
                        processedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        int threads = 10, perThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        Order o = Order.builder()
                            .id(UUID.randomUUID().toString())
                            .symbol("AAPL")
                            .side(i % 2 == 0 ? Side.BUY : Side.SELL)
                            .orderType(OrderType.LIMIT)
                            .price(new BigDecimal("150.00"))
                            .originalQuantity(10)
                            .remainingQuantity(10)
                            .status(OrderStatus.NEW)
                            .clientOrderId("T" + tid + "-" + i)
                            .createdAt(Instant.now())
                            .build();
                        ingress.submit(new EngineCommand.SubmitOrder(o));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        // give the worker thread time to drain the queue after producers finish
        Thread.sleep(2000);
        worker.interrupt();

        assertEquals(threads * perThread, processedCount.get(),
            "Some orders were lost or never processed");
    }
}