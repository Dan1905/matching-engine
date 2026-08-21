package com.trading.matching_engine.orderbook;

import com.trading.matching_engine.domain.Order;

/**
 * An order's slot in a price level's FIFO queue. Holding the links on the node — rather
 * than scanning a Deque for the order — is what makes cancel O(1): the engine's id index
 * hands back the node, and unlinking it touches only its two neighbours.
 */
public final class OrderNode {
    final Order order;
    OrderNode prev;
    OrderNode next;
    PriceLevel level;
    OrderBook book;

    OrderNode(Order order) {
        this.order = order;
    }

    public OrderBook book() { return book; }
    boolean isLinked() { return level != null; }
}
