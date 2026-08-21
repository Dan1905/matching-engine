package com.trading.matching_engine.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trading.matching_engine.domain.OrderSnapshot;
import com.trading.matching_engine.domain.Trade;
import com.trading.matching_engine.redis.OrderStatusCache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Takes all database and cache I/O off the matching thread.
 *
 * Delivery is at-least-once, not best-effort: nothing is ever dropped. A full queue
 * applies backpressure (the API stops accepting new orders — see isSaturated) rather
 * than discarding events, and a failing flush is retried before its batch is written
 * to the dead-letter log for replay.
 */
@Component
public class AsyncPersistenceWriter implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(AsyncPersistenceWriter.class);

    private static final int QUEUE_CAPACITY = 100_000;
    private static final int BATCH_SIZE = 500;
    private static final int MAX_FLUSH_ATTEMPTS = 4;
    private static final double SATURATION_THRESHOLD = 0.80;

    // Separate from the engine's ingress queue: matching thread is the sole PRODUCER
    // here, this writer thread the sole CONSUMER.
    private final BlockingQueue<WriteEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private final OrderRepository orderRepo;
    private final TradeRepository tradeRepo;
    private final OrderStatusCache statusCache;
    private final DeadLetterLog deadLetters;

    private volatile boolean running = true;
    private volatile boolean flushing = false;
    private Thread writerThread;

    private final AtomicLong blockedHandoffs = new AtomicLong();
    private final AtomicLong deadLetteredBatches = new AtomicLong();
    private final AtomicLong persistedEvents = new AtomicLong();

    public AsyncPersistenceWriter(OrderRepository orderRepo,
                                  TradeRepository tradeRepo,
                                  OrderStatusCache statusCache,
                                  DeadLetterLog deadLetters) {
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        this.statusCache = statusCache;
        this.deadLetters = deadLetters;
    }

    @PostConstruct
    public void start() {
        // Virtual thread is right here: this thread blocks on database and Redis I/O,
        // unlike the matching worker, which must stay pinned to a platform thread.
        this.writerThread = Thread.ofVirtual().name("persistence-writer").start(this);
    }

    /**
     * Called from the matching thread. Non-blocking in the normal case; if the queue is
     * genuinely full it blocks rather than dropping, because losing an execution record
     * is far worse than a latency spike. Backpressure at the API should stop the queue
     * ever reaching this point.
     */
    public void persist(WriteEvent event) {
        if (queue.offer(event)) return;
        blockedHandoffs.incrementAndGet();
        log.warn("Persistence queue full — matching thread blocking on hand-off. "
            + "Backpressure should have rejected upstream traffic before this.");
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deadLetters.append(List.of(event));
        }
    }

    /** True once the queue is deep enough that the API should shed load. */
    public boolean isSaturated() {
        return queue.size() >= QUEUE_CAPACITY * SATURATION_THRESHOLD;
    }

    public boolean isIdle() { return queue.isEmpty() && !flushing; }
    public int getQueueDepth() { return queue.size(); }
    public long getBlockedHandoffCount() { return blockedHandoffs.get(); }
    public long getDeadLetteredBatchCount() { return deadLetteredBatches.get(); }
    public long getPersistedEventCount() { return persistedEvents.get(); }

    @Override
    public void run() {
        log.info("AsyncPersistenceWriter started on {}", Thread.currentThread());
        List<WriteEvent> batch = new ArrayList<>(BATCH_SIZE);

        while (running || !queue.isEmpty()) {
            try {
                WriteEvent first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;

                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);

                flushing = true;
                try {
                    flushWithRetry(batch);
                } finally {
                    flushing = false;
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("AsyncPersistenceWriter stopped, queue drained");
    }

    /**
     * Both statements are idempotent (ON CONFLICT DO UPDATE / DO NOTHING), so replaying
     * a batch after a partial failure is safe.
     */
    private void flushWithRetry(List<WriteEvent> batch) throws InterruptedException {
        long backoffMs = 50;
        for (int attempt = 1; attempt <= MAX_FLUSH_ATTEMPTS; attempt++) {
            try {
                flush(batch);
                persistedEvents.addAndGet(batch.size());
                return;
            } catch (Exception e) {
                if (attempt == MAX_FLUSH_ATTEMPTS) {
                    log.error("Flush failed after {} attempts — dead-lettering {} events",
                        attempt, batch.size(), e);
                    deadLetters.append(batch);
                    deadLetteredBatches.incrementAndGet();
                    return;
                }
                log.warn("Flush attempt {}/{} failed ({}), retrying in {}ms",
                    attempt, MAX_FLUSH_ATTEMPTS, e.toString(), backoffMs);
                Thread.sleep(backoffMs);
                backoffMs *= 4;
            }
        }
    }

    private void flush(List<WriteEvent> batch) {
        List<OrderSnapshot> orders = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        // LinkedHashMap: last status for an id within the batch wins, and batches are
        // flushed in submission order by this single thread, so status never regresses.
        Map<String, String> statusUpdates = new LinkedHashMap<>();

        for (WriteEvent e : batch) {
            switch (e) {
                case WriteEvent.OrderEvent oe -> orders.add(oe.order());
                case WriteEvent.TradeEvent te -> trades.add(te.trade());
                case WriteEvent.StatusEvent se -> statusUpdates.put(se.orderId(), se.status());
            }
        }

        // Orders first: trades carry foreign keys onto orders(id).
        if (!orders.isEmpty()) orderRepo.batchInsert(orders);
        if (!trades.isEmpty()) tradeRepo.batchInsert(trades);

        // Redis is a cache rebuildable from Postgres, so a cache failure must not fail
        // (or retry) the durable write that already succeeded.
        if (!statusUpdates.isEmpty()) {
            try {
                statusCache.putAll(statusUpdates);
            } catch (Exception e) {
                log.warn("Status cache update failed for {} orders — reads fall back to Postgres",
                    statusUpdates.size(), e);
            }
        }
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running = false;
        if (writerThread != null) writerThread.join(10_000);
    }
}
