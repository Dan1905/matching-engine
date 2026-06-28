package com.trading.matching_engine.benchmark;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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

/**
 * Fair comparison point for SingleWriterBenchmark: this does the SAME unit of work —
 * insert an order into a price-sorted book — but guarded by a real lock instead of
 * relying on single-thread-only access. Multiple JMH threads call this CONCURRENTLY,
 * which is the scenario the lock is actually protecting against.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(8) // concurrent threads contending for the SAME lock — this is the real test
public class BaselineLockBenchmark {

    private TreeMap<BigDecimal, Queue<Order>> syncBids;
    private final Object syncLock = new Object();

    private TreeMap<BigDecimal, Queue<Order>> rwBids;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    @Setup(Level.Trial)
    public void setup() {
        syncBids = new TreeMap<>(Comparator.reverseOrder());
        rwBids   = new TreeMap<>(Comparator.reverseOrder());
    }

    @Benchmark
    public void synchronizedAddOrder() {
        Order order = makeOrder();
        synchronized (syncLock) {
            insertAndMaybeMatch(syncBids, order);
        }
    }

    @Benchmark
    public void readWriteLockAddOrder() {
        Order order = makeOrder();
        rwLock.writeLock().lock();
        try {
            insertAndMaybeMatch(rwBids, order);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Mirrors MatchingEngine's real per-order cost: TreeMap navigation + queue insert,
     * roughly equivalent computational work to what processOrder() does on the resting
     * side. Not the full matching algorithm, but representative work under the lock —
     * enough to make the comparison meaningful rather than trivial.
     */
    private void insertAndMaybeMatch(TreeMap<BigDecimal, Queue<Order>> book, Order order) {
        book.computeIfAbsent(order.getPrice(), p -> new ArrayDeque<>()).offer(order);
        // simulate checking the opposite side, same cost MatchingEngine pays per order
        if (!book.isEmpty()) {
            book.firstEntry().getValue().peek();
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