package com.trading.matching_engine;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.trading.matching_engine.domain.OrderSnapshot;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.domain.Trade;
import com.trading.matching_engine.persistence.AsyncPersistenceWriter;
import com.trading.matching_engine.persistence.DeadLetterLog;
import com.trading.matching_engine.persistence.OrderRepository;
import com.trading.matching_engine.persistence.TradeRepository;
import com.trading.matching_engine.persistence.WriteEvent;
import com.trading.matching_engine.redis.OrderStatusCache;

class AsyncPersistenceWriterTest {

    // --- test doubles ---------------------------------------------------------

    static class RecordingOrderRepo extends OrderRepository {
        final List<OrderSnapshot> written = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();

        RecordingOrderRepo() { super(null); }

        @Override
        public void batchInsert(List<OrderSnapshot> orders) {
            if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("simulated database outage");
            }
            written.addAll(orders);
        }
    }

    static class NoopTradeRepo extends TradeRepository {
        final List<Trade> written = new CopyOnWriteArrayList<>();
        NoopTradeRepo() { super(null); }
        @Override public void batchInsert(List<Trade> trades) { written.addAll(trades); }
    }

    static class RecordingCache extends OrderStatusCache {
        final List<Map<String, String>> batches = new CopyOnWriteArrayList<>();
        volatile boolean fail = false;
        RecordingCache() { super(null); }
        @Override public void putAll(Map<String, String> updates) {
            if (fail) throw new IllegalStateException("simulated Redis outage");
            batches.add(Map.copyOf(updates));
        }
    }

    private OrderSnapshot snapshot(String id, OrderStatus status, long remaining) {
        return new OrderSnapshot(id, "AAPL", Side.BUY, OrderType.LIMIT,
            new BigDecimal("100.00"), 100, remaining, status, "c-" + id, Instant.EPOCH);
    }

    private AsyncPersistenceWriter writer(RecordingOrderRepo orders, NoopTradeRepo trades,
                                          RecordingCache cache, Path deadLetterDir) {
        DeadLetterLog dlq = new DeadLetterLog(deadLetterDir.resolve("failed-writes.tsv").toString());
        AsyncPersistenceWriter w = new AsyncPersistenceWriter(orders, trades, cache, dlq);
        w.start();
        return w;
    }

    private void awaitIdle(AsyncPersistenceWriter w) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (w.isIdle()) { Thread.sleep(150); if (w.isIdle()) return; }
            Thread.sleep(20);
        }
        throw new AssertionError("writer never drained");
    }

    // --- tests ----------------------------------------------------------------

    @Test
    void everyEventIsPersisted_evenWhenTheProducerOutrunsTheWriter(@TempDir Path tmp) throws Exception {
        RecordingOrderRepo orders = new RecordingOrderRepo();
        NoopTradeRepo trades = new NoopTradeRepo();
        RecordingCache cache = new RecordingCache();
        AsyncPersistenceWriter w = writer(orders, trades, cache, tmp);

        int count = 50_000;
        for (int i = 0; i < count; i++) {
            w.persist(new WriteEvent.OrderEvent(snapshot("o" + i, OrderStatus.NEW, 100)));
        }
        awaitIdle(w);
        w.stop();

        assertEquals(count, orders.written.size(), "no event may be dropped");
        assertEquals(0, w.getDeadLetteredBatchCount());
    }

    @Test
    void aTransientDatabaseFailureIsRetried_notDiscarded(@TempDir Path tmp) throws Exception {
        RecordingOrderRepo orders = new RecordingOrderRepo();
        orders.failuresRemaining.set(2);            // fail twice, then succeed
        NoopTradeRepo trades = new NoopTradeRepo();
        RecordingCache cache = new RecordingCache();
        AsyncPersistenceWriter w = writer(orders, trades, cache, tmp);

        w.persist(new WriteEvent.OrderEvent(snapshot("retry-me", OrderStatus.FILLED, 0)));
        awaitIdle(w);
        w.stop();

        assertEquals(1, orders.written.size(), "the batch must survive a transient outage");
        assertEquals("retry-me", orders.written.get(0).id());
        assertEquals(0, w.getDeadLetteredBatchCount());
    }

    @Test
    void aPermanentFailureIsDeadLettered_notSilentlyDropped(@TempDir Path tmp) throws Exception {
        RecordingOrderRepo orders = new RecordingOrderRepo();
        orders.failuresRemaining.set(Integer.MAX_VALUE);   // never recovers
        NoopTradeRepo trades = new NoopTradeRepo();
        RecordingCache cache = new RecordingCache();
        AsyncPersistenceWriter w = writer(orders, trades, cache, tmp);

        w.persist(new WriteEvent.OrderEvent(snapshot("lost-in-db", OrderStatus.FILLED, 0)));
        awaitIdle(w);
        w.stop();

        assertEquals(0, orders.written.size());
        assertEquals(1, w.getDeadLetteredBatchCount());

        Path file = tmp.resolve("failed-writes.tsv");
        assertTrue(Files.exists(file), "undeliverable events must be recoverable from disk");
        String contents = Files.readString(file);
        assertTrue(contents.contains("lost-in-db"), contents);
        assertTrue(contents.startsWith("ORDER\t"), contents);
    }

    @Test
    void aCacheOutageDoesNotFailTheDurableWrite(@TempDir Path tmp) throws Exception {
        RecordingOrderRepo orders = new RecordingOrderRepo();
        NoopTradeRepo trades = new NoopTradeRepo();
        RecordingCache cache = new RecordingCache();
        cache.fail = true;
        AsyncPersistenceWriter w = writer(orders, trades, cache, tmp);

        w.persist(new WriteEvent.OrderEvent(snapshot("durable", OrderStatus.FILLED, 0)));
        w.persist(new WriteEvent.StatusEvent("durable", "FILLED"));
        awaitIdle(w);
        w.stop();

        assertEquals(1, orders.written.size(), "Redis is a cache; its failure must not lose the row");
        assertEquals(0, w.getDeadLetteredBatchCount());
    }

    @Test
    void statusUpdatesCollapseToTheLatestValueWithinABatch(@TempDir Path tmp) throws Exception {
        RecordingOrderRepo orders = new RecordingOrderRepo();
        NoopTradeRepo trades = new NoopTradeRepo();
        RecordingCache cache = new RecordingCache();
        AsyncPersistenceWriter w = writer(orders, trades, cache, tmp);

        w.persist(new WriteEvent.StatusEvent("o1", "NEW"));
        w.persist(new WriteEvent.StatusEvent("o1", "PARTIALLY_FILLED"));
        w.persist(new WriteEvent.StatusEvent("o1", "FILLED"));
        awaitIdle(w);
        w.stop();

        List<Map<String, String>> seen = new ArrayList<>(cache.batches);
        String last = null;
        for (Map<String, String> batch : seen) {
            if (batch.containsKey("o1")) last = batch.get("o1");
        }
        assertEquals("FILLED", last, "status must never regress");
    }

    @Test
    void saturationIsReportedBeforeTheQueueIsFull(@TempDir Path tmp) throws IOException {
        RecordingOrderRepo orders = new RecordingOrderRepo();
        DeadLetterLog dlq = new DeadLetterLog(tmp.resolve("dlq.tsv").toString());
        // deliberately not started: nothing drains the queue
        AsyncPersistenceWriter w = new AsyncPersistenceWriter(
            orders, new NoopTradeRepo(), new RecordingCache(), dlq);

        assertFalse(w.isSaturated());
        for (int i = 0; i < 81_000; i++) {
            w.persist(new WriteEvent.StatusEvent("o" + i, "NEW"));
        }
        assertTrue(w.isSaturated(), "the API must be told to shed load before the queue fills");
        assertEquals(0, w.getBlockedHandoffCount(), "backpressure, not blocking, at 81% depth");
        assertEquals(Collections.emptyList(), orders.written);
    }
}
