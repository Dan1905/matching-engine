package com.trading.matching_engine;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderSnapshot;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.domain.Trade;
import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.MatchingEngineWorker;
import com.trading.matching_engine.engine.OrderIngress;
import com.trading.matching_engine.engine.QueueBasedIngress;
import com.trading.matching_engine.matching.MatchingEngine;
import com.trading.matching_engine.persistence.AsyncPersistenceWriter;
import com.trading.matching_engine.persistence.DeadLetterLog;
import com.trading.matching_engine.persistence.OrderRepository;
import com.trading.matching_engine.persistence.TradeRepository;
import com.trading.matching_engine.redis.OrderStatusCache;

/**
 * The worker end to end with fake repositories: matching thread -> persistence queue ->
 * writer thread, which is exactly the boundary the snapshot fix protects.
 */
class MatchingEngineWorkerTest {

    static class Repo extends OrderRepository {
        final List<OrderSnapshot> rows = new CopyOnWriteArrayList<>();
        Repo() { super(null); }
        @Override public void batchInsert(List<OrderSnapshot> orders) { rows.addAll(orders); }
    }

    static class Trades extends TradeRepository {
        final List<Trade> rows = new CopyOnWriteArrayList<>();
        Trades() { super(null); }
        @Override public void batchInsert(List<Trade> trades) { rows.addAll(trades); }
    }

    static class Cache extends OrderStatusCache {
        Cache() { super(null); }
        @Override public void putAll(java.util.Map<String, String> updates) { }
    }

    private record Harness(OrderIngress ingress, MatchingEngineWorker worker,
                           Thread thread, AsyncPersistenceWriter writer,
                           Repo orders, Trades trades) {}

    private Harness start(Path tmp) {
        OrderIngress ingress = new QueueBasedIngress();
        MatchingEngine engine = new MatchingEngine();
        Repo orders = new Repo();
        Trades trades = new Trades();
        AsyncPersistenceWriter writer = new AsyncPersistenceWriter(orders, trades, new Cache(),
            new DeadLetterLog(tmp.resolve("dlq.tsv").toString()));
        writer.start();

        MatchingEngineWorker worker = new MatchingEngineWorker(ingress, engine, writer);
        Thread thread = Thread.ofPlatform().name("test-matching-worker").start(worker);
        return new Harness(ingress, worker, thread, writer, orders, trades);
    }

    private void drain(Harness h) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (h.worker().isIdle()) { Thread.sleep(200); if (h.worker().isIdle()) break; }
            Thread.sleep(20);
        }
        h.worker().stop();
        h.thread().interrupt();
        h.thread().join(2_000);
        h.writer().stop();
    }

    private Order limit(String symbol, Side side, String price, long qty) {
        return Order.builder()
            .id(UUID.randomUUID().toString())
            .symbol(symbol).side(side).orderType(OrderType.LIMIT)
            .price(new BigDecimal(price))
            .originalQuantity(qty).remainingQuantity(qty)
            .status(OrderStatus.NEW)
            .clientOrderId("c-" + UUID.randomUUID())
            .createdAt(Instant.now())
            .build();
    }

    @Test
    void persistedSnapshotsRecordEveryIntermediateState(@TempDir Path tmp) throws Exception {
        Harness h = start(tmp);

        Order resting = limit("AAPL", Side.SELL, "100.00", 100);
        h.ingress().submit(new EngineCommand.SubmitOrder(resting));
        h.ingress().submit(new EngineCommand.SubmitOrder(limit("AAPL", Side.BUY, "100.00", 40)));
        h.ingress().submit(new EngineCommand.SubmitOrder(limit("AAPL", Side.BUY, "100.00", 60)));

        drain(h);

        List<OrderSnapshot> restingRows = h.orders().rows.stream()
            .filter(r -> r.id().equals(resting.getId())).toList();

        // NEW(100) on arrival, PARTIALLY_FILLED(60) after the first buy, FILLED(0) after
        // the second. Publishing the live Order instead of snapshots would have written
        // whatever the matching thread happened to hold at flush time — three times FILLED.
        assertEquals(3, restingRows.size(), restingRows.toString());
        assertEquals(100, restingRows.get(0).remainingQuantity());
        assertEquals(OrderStatus.NEW, restingRows.get(0).status());
        assertEquals(60, restingRows.get(1).remainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, restingRows.get(1).status());
        assertEquals(0, restingRows.get(2).remainingQuantity());
        assertEquals(OrderStatus.FILLED, restingRows.get(2).status());

        assertEquals(2, h.trades().rows.size());
    }

    @Test
    void cancelIsAppliedByTheWorkerAndPersisted(@TempDir Path tmp) throws Exception {
        Harness h = start(tmp);

        Order resting = limit("AAPL", Side.BUY, "100.00", 100);
        h.ingress().submit(new EngineCommand.SubmitOrder(resting));
        h.ingress().submit(new EngineCommand.CancelOrder(resting.getId()));

        drain(h);

        List<OrderSnapshot> rows = h.orders().rows.stream()
            .filter(r -> r.id().equals(resting.getId())).toList();
        assertEquals(OrderStatus.CANCELLED, rows.get(rows.size() - 1).status());
    }

    @Test
    void unfillableMarketOrderIsPersistedAsRejected(@TempDir Path tmp) throws Exception {
        Harness h = start(tmp);

        Order market = Order.builder()
            .id(UUID.randomUUID().toString())
            .symbol("AAPL").side(Side.BUY).orderType(OrderType.MARKET)
            .originalQuantity(100).remainingQuantity(100)
            .status(OrderStatus.NEW).clientOrderId("c-mkt").createdAt(Instant.now())
            .build();
        h.ingress().submit(new EngineCommand.SubmitOrder(market));

        drain(h);

        assertEquals(1, h.orders().rows.size());
        assertEquals(OrderStatus.REJECTED, h.orders().rows.get(0).status());
        assertTrue(h.trades().rows.isEmpty());
    }

    @Test
    void symbolsStayIsolatedThroughTheFullPipeline(@TempDir Path tmp) throws Exception {
        Harness h = start(tmp);

        h.ingress().submit(new EngineCommand.SubmitOrder(limit("AAPL", Side.BUY, "100.00", 100)));
        h.ingress().submit(new EngineCommand.SubmitOrder(limit("TSLA", Side.SELL, "100.00", 100)));

        drain(h);

        assertTrue(h.trades().rows.isEmpty(), "no trade may be printed across two symbols");
    }
}
