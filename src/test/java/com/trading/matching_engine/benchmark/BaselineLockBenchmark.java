package com.trading.matching_engine.benchmark;

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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.trading.matching_engine.matching.MatchResult;
import com.trading.matching_engine.matching.MatchingEngine;

/**
 * The comparison point for SingleWriterBenchmark.
 *
 * It runs THE SAME MatchingEngine.processOrder() on THE SAME order stream from THE SAME
 * number of threads ({@link Benchmarks#PRODUCER_THREADS}) — the only difference is that
 * access is serialized by a lock instead of by handing work to a dedicated thread. That
 * makes the delta attributable to the concurrency model and nothing else.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@Threads(Benchmarks.PRODUCER_THREADS)
public class BaselineLockBenchmark {

    private MatchingEngine syncEngine;
    private final Object monitor = new Object();

    private MatchingEngine rwEngine;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** Per-thread order stream — generation must not be part of what is contended. */
    @State(Scope.Thread)
    public static class Producer {
        OrderFlow flow;
        @Setup(Level.Iteration)
        public void setup() { flow = new OrderFlow(Thread.currentThread().threadId()); }
    }

    @Setup(Level.Iteration)
    public void setup() {
        syncEngine = new MatchingEngine();
        rwEngine = new MatchingEngine();
    }

    @Benchmark
    public void synchronizedProcessOrder(Producer p, Blackhole bh) {
        synchronized (monitor) {
            MatchResult r = syncEngine.processOrder(p.flow.next());
            bh.consume(r);
        }
    }

    @Benchmark
    public void readWriteLockProcessOrder(Producer p, Blackhole bh) {
        rwLock.writeLock().lock();
        try {
            MatchResult r = rwEngine.processOrder(p.flow.next());
            bh.consume(r);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(BaselineLockBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
