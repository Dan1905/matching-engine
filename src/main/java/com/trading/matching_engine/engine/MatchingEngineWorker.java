package com.trading.matching_engine.engine;




import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.matching.MatchResult;
import com.trading.matching_engine.matching.MatchingEngine;
import com.trading.matching_engine.persistence.AsyncPersistenceWriter;
import com.trading.matching_engine.persistence.WriteEvent;
import com.trading.matching_engine.redis.OrderStatusCache;

@Component
public class MatchingEngineWorker implements Runnable{
     private static final Logger log = LoggerFactory.getLogger(MatchingEngineWorker.class);

    private final OrderIngress ingress;
    private final MatchingEngine engine;
    private final AsyncPersistenceWriter writer;
    private final OrderStatusCache statusCache;
    private volatile boolean running = true;
    private volatile boolean busy = false;

    public MatchingEngineWorker(OrderIngress ingress,
                                 MatchingEngine engine,
                                 AsyncPersistenceWriter writer,
                                 OrderStatusCache statusCache) {
        this.ingress = ingress;
        this.engine = engine;
        this.writer = writer;
        this.statusCache = statusCache;
    }

    @Override
    public void run() {
        log.info("MatchingEngineWorker started on thread: {}", Thread.currentThread().getName());

        while (running) {
            try {
                EngineCommand command = ingress.take();

                switch (command) {
                    case EngineCommand.SubmitOrder cmd -> handleSubmit(cmd.order());
                    case EngineCommand.CancelOrder cmd -> handleCancel(cmd);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing command — worker continues", e);
            }
        }

        log.info("MatchingEngineWorker stopped");
    }

    private void handleSubmit(Order order) {
    busy = true;
        try {
            MatchResult result = engine.processOrder(order);

            result.getUpdatedOrders().forEach(updated ->
                writer.persist(new WriteEvent.OrderEvent(updated)));
            result.getTrades().forEach(t -> writer.persist(new WriteEvent.TradeEvent(t)));
            result.getUpdatedOrders().forEach(updated ->
                writer.persist(new WriteEvent.StatusEvent(updated.getId(), updated.getStatus().name())));
            // NO direct statusCache.put() call here anymore — matching thread never touches Redis
        } finally {
            busy = false;
            }
    }

    private void handleCancel(EngineCommand.CancelOrder cmd) {
    engine.cancel(cmd.orderId(), cmd.side(), cmd.price()).ifPresentOrElse(
        cancelledOrder -> {
            writer.persist(new WriteEvent.OrderEvent(cancelledOrder));
            writer.persist(new WriteEvent.StatusEvent(cmd.orderId(),
                com.trading.matching_engine.domain.OrderStatus.CANCELLED.name()));
            statusCache.put(cmd.orderId(),
                com.trading.matching_engine.domain.OrderStatus.CANCELLED.name());
        },
        () -> log.warn("Cancel requested for order not found in book: {}", cmd.orderId())
        );
    }

    public void stop() {
        running = false;
    }

    public boolean isIdle() {
        return !busy && ingress.isEmpty() && writer.isIdle();
    }
}
