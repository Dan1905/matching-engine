package com.trading.matching_engine.benchmark;

/** Shared benchmark constants, so both sides of the comparison stay in step. */
final class Benchmarks {
    /**
     * Producer thread count, identical on the lock and single-writer benchmarks.
     *
     * Kept at or below the machine's performance-core count on purpose. The
     * single-writer design has ONE matching thread; if the producers saturate every
     * core the scheduler starves it, and the benchmark measures core contention rather
     * than the concurrency model. The lock benchmark does its work on the calling
     * threads and never pays that cost, so oversubscribing would quietly bias the
     * comparison in its favour.
     */
    static final int PRODUCER_THREADS = 4;

    private Benchmarks() {}
}
