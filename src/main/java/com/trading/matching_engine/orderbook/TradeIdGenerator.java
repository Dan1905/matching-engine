package com.trading.matching_engine.orderbook;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Trade ids for the hot path. UUID.randomUUID() draws from a shared SecureRandom — a
 * lock and a few microseconds on the one thread that must never pay either. A random
 * per-run prefix plus a monotonic counter is unique across restarts, ordered within a
 * run, and costs a string concat.
 *
 * Single-writer: not thread safe by design.
 */
public final class TradeIdGenerator {
    private final String prefix;
    private long sequence;

    public TradeIdGenerator() {
        this.prefix = Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 4) + "-";
    }

    public String next() {
        return prefix + (++sequence);
    }
}
