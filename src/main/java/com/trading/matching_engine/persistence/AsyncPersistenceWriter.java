package com.trading.matching_engine.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.Trade;

import jakarta.annotation.PreDestroy;
@Component
public class AsyncPersistenceWriter implements Runnable{
    private static final Logger log = LoggerFactory.getLogger(AsyncPersistenceWriter.class);
    private static final int QUEUE_CAPACITY = 100_000;
    private static final int BATCH_SIZE = 500;

    // This queue is SEPARATE from the engine's ingress queue — different concern entirely.
    // Matching thread is a PRODUCER here; this writer thread is the CONSUMER.
    private final LinkedBlockingQueue<WriteEvent> queue =
        new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final OrderRepository orderRepo;
    private final TradeRepository tradeRepo;
    private volatile boolean running = true;
    private volatile boolean flushing = false;
    private Thread writerThread;

    public AsyncPersistenceWriter(OrderRepository orderRepo, TradeRepository tradeRepo) {
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        // virtual thread is correct here — this thread blocks on DB I/O, unlike the matching worker
        this.writerThread = Thread.ofVirtual().name("persistence-writer").start(this);
    }

    // called from the matching thread — must NEVER block
    public void persist(WriteEvent event) {
        if (!queue.offer(event)) {
            log.warn("Persistence queue full — dropping event: {}", event);
        }
    }

    public boolean isIdle() {
        return queue.isEmpty() && !flushing;
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

        for (WriteEvent e : batch) {
            switch (e) {
                case WriteEvent.OrderEvent oe -> orders.add(oe.order());
                case WriteEvent.TradeEvent te -> trades.add(te.trade());
            }
        }

        if (!orders.isEmpty()) orderRepo.batchInsert(orders);
        if (!trades.isEmpty()) tradeRepo.batchInsert(trades);

        log.debug("Flushed {} orders, {} trades", orders.size(), trades.size());
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running = false;
        writerThread.join(5_000);
        log.info("AsyncPersistenceWriter stopped, queue drained");
    }
}
