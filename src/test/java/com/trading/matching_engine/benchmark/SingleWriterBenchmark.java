package com.trading.matching_engine.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
 * Single-writer throughput under the SAME load as BaselineLockBenchmark: identical
 * order stream, identical producer thread count, identical processOrder() call.
 *
 * The honesty problem with benchmarking a queue is that producers run ahead of the
 * consumer and you end up timing queue.offer(). This caps the in-flight window, so a
 * producer cannot get further than IN_FLIGHT_WINDOW orders ahead of the matching
 * thread — the reported throughput is gated by real matching work, not hand-off.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@Threads(Benchmarks.PRODUCER_THREADS)
public class SingleWriterBenchmark {

    private static final int IN_FLIGHT_WINDOW = 256;

    private OrderIngress ingress;
    private MatchingEngine engine;
    private Thread workerThread;
    private volatile boolean running;

    private final AtomicLong submitted = new AtomicLong();
    private volatile long processed;   // written only by the worker thread

    @State(Scope.Thread)
    public static class Producer {
        OrderFlow flow;
        @Setup(Level.Iteration)
        public void setup() { flow = new OrderFlow(Thread.currentThread().threadId()); }
    }

    @Setup(Level.Iteration)
    public void setup() {
        ingress = new QueueBasedIngress();
        engine = new MatchingEngine();
        submitted.set(0);
        processed = 0;
        running = true;

        workerThread = Thread.ofPlatform().name("bench-matching-worker").start(() -> {
            while (running) {
                try {
                    EngineCommand cmd = ingress.take();
                    if (cmd instanceof EngineCommand.SubmitOrder submit) {
                        engine.processOrder(submit.order());
                        processed++;   // single writer — no atomic needed
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
    public void submitAndMatch(Producer p, Blackhole bh) {
        while (!ingress.submit(new EngineCommand.SubmitOrder(p.flow.next()))) {
            Thread.yield();
        }
        long mine = submitted.incrementAndGet();

        // Throttle to the matching thread's actual rate. Yield rather than spin: a
        // hot spin here burns the very cores the single matching thread needs, which
        // would make this benchmark measure scheduler starvation.
        int spins = 0;
        while (mine - processed > IN_FLIGHT_WINDOW) {
            if (++spins < 64) Thread.onSpinWait(); else { Thread.yield(); spins = 0; }
        }
        bh.consume(processed);
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(SingleWriterBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
