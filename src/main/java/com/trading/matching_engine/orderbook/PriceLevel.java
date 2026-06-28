package com.trading.matching_engine.orderbook;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Queue;

import com.trading.matching_engine.domain.Order;

public class PriceLevel {
    private final BigDecimal price;
    private final Queue<Order> orders = new ArrayDeque<>();
    public PriceLevel(BigDecimal price) {
        this.price = price;
    }

    public void addOrder(Order order) {
        orders.offer(order);
    }

    public Order peek() {
        return orders.peek();
    }

    public void removeHead() {
        orders.poll();
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void cancel(String orderId) {
        orders.removeIf(order -> order.getId().equals(orderId));
    }
}
