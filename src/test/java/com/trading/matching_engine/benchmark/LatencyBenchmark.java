package com.trading.matching_engine.benchmark;

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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.OrderIngress;
import com.trading.matching_engine.engine.QueueBasedIngress;
import com.trading.matching_engine.matching.MatchingEngine;

/**
 * Per-order LATENCY, in microseconds, with percentiles — the number a throughput
 * benchmark cannot give you and the only basis for any claim about how fast a single
 * order is handled.
 *
 * Two measurements, because they answer different questions:
 *  - matchOrder:      time inside MatchingEngine.processOrder(). The algorithm itself.
 *  - submitToMatched: producer hand-off through the queue until the worker has finished
 *                     the order. What a caller actually waits for, minus HTTP and I/O.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(2)
@Threads(1)
public class LatencyBenchmark {

    private MatchingEngine directEngine;
    private OrderFlow directFlow;

    private OrderIngress ingress;
    private OrderFlow queuedFlow;
    private Thread workerThread;
    private volatile boolean running;
    private volatile long processed;

    @Setup(Level.Iteration)
    public void setup() {
        directEngine = new MatchingEngine();
        directFlow = new OrderFlow(42);

        ingress = new QueueBasedIngress();
        queuedFlow = new OrderFlow(43);
        MatchingEngine queuedEngine = new MatchingEngine();
        processed = 0;
        running = true;

        workerThread = Thread.ofPlatform().name("latency-bench-worker").start(() -> {
            while (running) {
                try {
                    EngineCommand cmd = ingress.take();
                    if (cmd instanceof EngineCommand.SubmitOrder submit) {
                        queuedEngine.processOrder(submit.order());
                        processed++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    @TearDown(Level.Iteration)
    public void teardown() throws InterruptedException {
        running = false;
        workerThread.interrupt();
        workerThread.join(2_000);
    }

    @Benchmark
    public void matchOrder(Blackhole bh) {
        bh.consume(directEngine.processOrder(directFlow.next()));
    }

    @Benchmark
    public void submitToMatched(Blackhole bh) {
        long target = processed + 1;
        while (!ingress.submit(new EngineCommand.SubmitOrder(queuedFlow.next()))) {
            Thread.onSpinWait();
        }
        while (processed < target) {
            Thread.onSpinWait();
        }
        bh.consume(processed);
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(LatencyBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
