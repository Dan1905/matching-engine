package com.trading.matching_engine.orderbook;

import com.trading.matching_engine.domain.Order;

/**
 * One price in the book: a FIFO queue of resting orders, giving time priority within
 * the price. Intrusive doubly-linked list — append, peek and remove are all O(1) with
 * no iteration and no allocation beyond the node itself.
 */
public final class PriceLevel {
    private final long priceTicks;
    private OrderNode head;
    private OrderNode tail;

    PriceLevel(long priceTicks) {
        this.priceTicks = priceTicks;
    }

    OrderNode addLast(Order order) {
        OrderNode node = new OrderNode(order);
        node.level = this;
        if (tail == null) {
            head = tail = node;
        } else {
            tail.next = node;
            node.prev = tail;
            tail = node;
        }
        return node;
    }

    Order peek() {
        return head == null ? null : head.order;
    }

    void unlink(OrderNode node) {
        if (node.level != this) return;
        if (node.prev != null) node.prev.next = node.next; else head = node.next;
        if (node.next != null) node.next.prev = node.prev; else tail = node.prev;
        node.prev = node.next = null;
        node.level = null;
    }

    boolean isEmpty() { return head == null; }

    public long getPriceTicks() { return priceTicks; }
}
