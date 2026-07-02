package com.trading.matching_engine.persistence;



import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.Trade;
import com.trading.matching_engine.redis.OrderStatusCache;

import jakarta.annotation.PreDestroy;

@Component
public class AsyncPersistenceWriter implements Runnable{
    private static final Logger log = LoggerFactory.getLogger(AsyncPersistenceWriter.class);
    private static final int QUEUE_CAPACITY = 100_000;
    private static final int BATCH_SIZE = 500;

    // This queue is SEPARATE from the engine's ingress queue — different concern entirely.
    // Matching thread is a PRODUCER here; this writer thread is the CONSUMER.
    private final BlockingQueue<WriteEvent> queue =
        new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private final OrderRepository orderRepo;
    private final TradeRepository tradeRepo;
    private volatile boolean running = true;
    private volatile boolean flushing = false;
    private Thread writerThread;
    private final OrderStatusCache statusCache;
    private final AtomicLong droppedEvents = new AtomicLong(0);

    public AsyncPersistenceWriter(OrderRepository orderRepo, TradeRepository tradeRepo, OrderStatusCache statusCache) {
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        this.statusCache = statusCache;
        // virtual thread is correct here — this thread blocks on DB I/O, unlike the matching worker
        this.writerThread = Thread.ofVirtual().name("persistence-writer").start(this);
    }

    // called from the matching thread — must NEVER block
    public void persist(WriteEvent event) {
    boolean offered = false;
        try {
            offered = queue.offer(event, 5, TimeUnit.MILLISECONDS); // brief bounded wait, not zero-wait drop
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!offered) {
            long total = droppedEvents.incrementAndGet();
            log.error("Persistence queue full — DROPPED event #{}: {}", total, event);
            // TODO: wire to a metrics counter / alert — this is a correctness incident, not a warning
        }
    }

    public boolean isIdle() {
        return queue.isEmpty() && !flushing;
    }

    public long getDroppedEventCount() {
        return droppedEvents.get();
    }
    @Override
    public void run() {
        List<WriteEvent> batch = new ArrayList<>(BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                WriteEvent first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;

                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);

                flushing = true;
                try {
                    flush(batch);
                } finally {
                    flushing = false;
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Persistence flush failed — batch dropped", e);
                flushing = false;
                batch.clear();
            }
        }
    }

    private void flush(List<WriteEvent> batch) {
        List<Order> orders = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        Map<String, String> statusUpdates = new HashMap<>();

        for (WriteEvent e : batch) {
            switch (e) {
                case WriteEvent.OrderEvent oe -> orders.add(oe.order());
                case WriteEvent.TradeEvent te -> trades.add(te.trade());
                case WriteEvent.StatusEvent se -> statusUpdates.put(se.orderId(), se.status());
            }
        }

        if (!orders.isEmpty()) orderRepo.batchInsert(orders);
        if (!trades.isEmpty()) tradeRepo.batchInsert(trades);
        if (!statusUpdates.isEmpty()) statusCache.putAll(statusUpdates); // pipelined batch write
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running = false;
        writerThread.join(5_000);
        log.info("AsyncPersistenceWriter stopped, queue drained");
    }
}
