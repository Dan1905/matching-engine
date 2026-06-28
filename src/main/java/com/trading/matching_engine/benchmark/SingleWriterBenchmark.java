package com.trading.matching_engine.benchmark;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.OrderIngress;
import com.trading.matching_engine.engine.QueueBasedIngress;
import com.trading.matching_engine.matching.MatchingEngine;

/**
 * Measures REAL matching throughput — submit a batch, then BLOCK until the single
 * worker thread has actually drained and matched every order in it. This is the only
 * honest way to benchmark this architecture: the @Benchmark method's timed duration
 * must include the worker's processing time, not just the producer's queue.offer() cost.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SingleWriterBenchmark {

    private static final int BATCH_SIZE = 1000;

    private OrderIngress ingress;
    private MatchingEngine engine;
    private Thread workerThread;
    private volatile boolean running;
    private volatile long processedCount; // only ever written by worker thread

    @Setup(Level.Trial)
    public void setup() {
        ingress = new QueueBasedIngress();
        engine = new MatchingEngine();
        running = true;
        processedCount = 0;

        workerThread = Thread.ofPlatform().start(() -> {
            while (running) {
                try {
                    EngineCommand cmd = ingress.take();
                    if (cmd instanceof EngineCommand.SubmitOrder submit) {
                        engine.processOrder(submit.order());
                        processedCount++; // single writer thread — safe without atomic
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @TearDown(Level.Trial)
    public void teardown() {
        running = false;
        workerThread.interrupt();
    }

    /**
     * Submits a batch of orders from THIS benchmark thread (single producer — JMH @Threads
     * defaults to 1 unless overridden), then spins until processedCount confirms the worker
     * has actually matched all of them. The JMH timer wraps this entire method, so the
     * reported throughput reflects real matching work, not just the hand-off.
     */
    @Benchmark
    public void submitAndDrainBatch(Blackhole bh) throws InterruptedException {
        long startCount = processedCount;

        for (int i = 0; i < BATCH_SIZE; i++) {
            Order order = makeOrder();
            while (!ingress.submit(new EngineCommand.SubmitOrder(order))) {
                // queue full — brief backoff, don't busy-spin too hard
                Thread.onSpinWait();
            }
        }

        // block until the worker has actually processed this whole batch
        long target = startCount + BATCH_SIZE;
        while (processedCount < target) {
            Thread.onSpinWait();
        }

        bh.consume(processedCount); // prevent JIT from optimizing the field read away
    }

    private Order makeOrder() {
        return Order.builder()
            .id(UUID.randomUUID().toString())
            .symbol("AAPL")
            .side(Math.random() < 0.5 ? Side.BUY : Side.SELL)
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
            .include(SingleWriterBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}