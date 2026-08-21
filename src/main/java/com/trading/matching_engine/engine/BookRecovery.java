package com.trading.matching_engine.engine;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.matching.MatchingEngine;
import com.trading.matching_engine.persistence.OrderRepository;

/**
 * Rebuilds the order book from Postgres at startup.
 *
 * The book is in-memory only. Without this, a restart leaves every resting order alive
 * in the database as NEW/PARTIALLY_FILLED while the book that owned it is gone — clients
 * hold orders the engine has no record of.
 *
 * Orders are replayed in creation order and placed directly, never matched: a consistent
 * book holds no crossing orders, so re-matching them would invent trades that never
 * happened. A crossed result means the persisted state was already inconsistent, and is
 * logged loudly rather than papered over.
 */
@Component
public class BookRecovery {
    private static final Logger log = LoggerFactory.getLogger(BookRecovery.class);

    private final OrderRepository orderRepo;
    private final MatchingEngine engine;
    private final boolean enabled;

    public BookRecovery(OrderRepository orderRepo,
                        MatchingEngine engine,
                        @Value("${engine.recovery.enabled:true}") boolean enabled) {
        this.orderRepo = orderRepo;
        this.engine = engine;
        this.enabled = enabled;
    }

    /** Called before the matching thread starts, so it needs no synchronization. */
    public int recover() {
        if (!enabled) {
            log.warn("Book recovery DISABLED — starting with an empty book");
            return 0;
        }

        long start = System.nanoTime();
        List<Order> open = orderRepo.findOpenOrders();
        open.forEach(engine::restore);
        engine.verifyUncrossed();

        log.info("Recovered {} resting orders into the book in {} ms",
            open.size(), (System.nanoTime() - start) / 1_000_000);
        return open.size();
    }
}
