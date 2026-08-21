package com.trading.matching_engine.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.matching.MatchResult;
import com.trading.matching_engine.matching.MatchingEngine;
import com.trading.matching_engine.persistence.AsyncPersistenceWriter;
import com.trading.matching_engine.persistence.WriteEvent;

/**
 * The single writer. This is the only thread that ever touches the order book, and it
 * performs no I/O of its own — every database and cache write is handed to the
 * persistence queue as an immutable snapshot.
 */
@Component
public class MatchingEngineWorker implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MatchingEngineWorker.class);

    private final OrderIngress ingress;
    private final MatchingEngine engine;
    private final AsyncPersistenceWriter writer;

    private volatile boolean running = true;
    private volatile boolean busy = false;
    private volatile long processedCommands = 0;

    public MatchingEngineWorker(OrderIngress ingress,
                                MatchingEngine engine,
                                AsyncPersistenceWriter writer) {
        this.ingress = ingress;
        this.engine = engine;
        this.writer = writer;
    }

    @Override
    public void run() {
        log.info("MatchingEngineWorker started on thread: {}", Thread.currentThread().getName());

        while (running) {
            try {
                EngineCommand command = ingress.take();
                busy = true;
                try {
                    switch (command) {
                        case EngineCommand.SubmitOrder cmd -> handleSubmit(cmd.order());
                        case EngineCommand.CancelOrder cmd -> handleCancel(cmd.orderId());
                    }
                    processedCommands++;
                } finally {
                    busy = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("MatchingEngineWorker stopped after {} commands", processedCommands);
    }

    private void handleSubmit(Order order) {
        MatchResult result;
        try {
            result = engine.processOrder(order);
        } catch (RuntimeException e) {
            // The client already holds a 202 and an order id. Failing silently would
            // leave them polling a status that never changes, so reject explicitly.
            log.error("Matching failed for {} — rejecting", order, e);
            order.setStatus(OrderStatus.REJECTED);
            publish(order);
            return;
        }

        for (Order updated : result.updatedOrders()) {
            writer.persist(new WriteEvent.OrderEvent(updated.snapshot()));
        }
        result.trades().forEach(t -> writer.persist(new WriteEvent.TradeEvent(t)));
        for (Order updated : result.updatedOrders()) {
            writer.persist(new WriteEvent.StatusEvent(updated.getId(), updated.getStatus().name()));
        }
    }

    private void handleCancel(String orderId) {
        engine.cancel(orderId).ifPresentOrElse(
            this::publish,
            () -> log.warn("Cancel requested for an order not resting in the book: {}", orderId));
    }

    /**
     * Snapshot, then hand off. The matching thread keeps mutating the live Order after
     * this returns; only the immutable copy crosses to the writer thread.
     */
    private void publish(Order order) {
        writer.persist(new WriteEvent.OrderEvent(order.snapshot()));
        writer.persist(new WriteEvent.StatusEvent(order.getId(), order.getStatus().name()));
    }

    public void stop() { running = false; }

    public boolean isIdle() { return !busy && ingress.isEmpty() && writer.isIdle(); }
}
