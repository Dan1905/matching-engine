package com.trading.matching_engine.benchmark;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(8)
public class BaselineLockBenchmark {
    // ── synchronized baseline ─────────────────────────────────────
    private TreeMap<BigDecimal, java.util.Queue<Order>> syncBids;
    private final Object syncLock = new Object();

    // ── RWLock baseline ───────────────────────────────────────────
    private TreeMap<BigDecimal, java.util.Queue<Order>> rwBids;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    @Setup
    public void setup() {
        syncBids = new TreeMap<>(Comparator.reverseOrder());
        rwBids   = new TreeMap<>(Comparator.reverseOrder());
    }

    @Benchmark
    public void synchronizedAddOrder() {
        Order order = makeOrder();
        synchronized (syncLock) {
            syncBids.computeIfAbsent(order.getPrice(), p -> new ArrayDeque<>()).offer(order);
        }
    }

    @Benchmark
    public void readWriteLockAddOrder() {
        Order order = makeOrder();
        rwLock.writeLock().lock();
        try {
            rwBids.computeIfAbsent(order.getPrice(), p -> new ArrayDeque<>()).offer(order);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private Order makeOrder() {
        return Order.builder()
            .id(UUID.randomUUID().toString())
            .symbol("AAPL")
            .side(Side.BUY)
            .orderType(OrderType.LIMIT)
            .price(new BigDecimal("150.00"))
            .originalQuantity(100)
            .remainingQuantity(100)
            .status(OrderStatus.NEW)
            .clientOrderId(UUID.randomUUID().toString())
            .createdAt(Instant.now())
            .build();
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(BaselineLockBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
