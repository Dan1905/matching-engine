package com.trading.matching_engine.benchmark;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
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

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(8) 
public class SingleWriterBenchmark {
    private OrderIngress ingress;
    private MatchingEngine engine;
    private Thread workerThread;
    private volatile boolean running;

    @Setup
    public void setup() {
        ingress = new QueueBasedIngress();
        engine = new MatchingEngine();
        running = true;

        // manually start a worker thread here since this isn't a Spring context —
        // this mirrors exactly what EngineLifecycle does in the real app
        workerThread = Thread.ofPlatform().start(() -> {
            while (running) {
                try {
                    EngineCommand cmd = ingress.take();
                    if (cmd instanceof EngineCommand.SubmitOrder submit) {
                        engine.processOrder(submit.order());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @TearDown
    public void teardown() {
        running = false;
        workerThread.interrupt();
    }

    @Benchmark
    public void submitOrder() {
        Order order = Order.builder()
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

        // this is what we're actually measuring — multiple JMH threads calling submit()
        // concurrently, one real worker thread draining and matching
        ingress.submit(new EngineCommand.SubmitOrder(order));
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(SingleWriterBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
