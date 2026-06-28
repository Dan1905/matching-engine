package com.trading.matching_engine.matching;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.domain.Trade;
import com.trading.matching_engine.orderbook.PriceLevel;

@Component
public class MatchingEngine {
    private final TreeMap<BigDecimal, PriceLevel> bids =
        new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<BigDecimal, PriceLevel> asks =
        new TreeMap<>();

    private void matchBuyOrder(Order incoming, List<Trade> trades, List<Order> updatedOrders) {
        while (incoming.getRemainingQuantity() > 0 && !asks.isEmpty()) {
            PriceLevel bestAskLevel = asks.firstEntry().getValue();

            boolean crosses = incoming.getOrderType() == OrderType.MARKET
                || incoming.getPrice().compareTo(bestAskLevel.getPrice()) >= 0;
            if (!crosses) break;

            Order resting = bestAskLevel.peek();
            long fillQty = Math.min(incoming.getRemainingQuantity(), resting.getRemainingQuantity());

            trades.add(Trade.builder()
                .id(UUID.randomUUID().toString())
                .symbol(incoming.getSymbol())
                .buyOrderId(incoming.getId())
                .sellOrderId(resting.getId())
                .executedPrice(bestAskLevel.getPrice())
                .executedQty(fillQty)
                .executedAt(Instant.now())
                .build());

            incoming.setRemainingQuantity(incoming.getRemainingQuantity() - fillQty);
            resting.setRemainingQuantity(resting.getRemainingQuantity() - fillQty);
            updateStatus(incoming);
            updateStatus(resting);
            if (!updatedOrders.contains(resting)) updatedOrders.add(resting);

            if (resting.getRemainingQuantity() == 0) bestAskLevel.removeHead();
            if (bestAskLevel.isEmpty()) asks.pollFirstEntry();
        }
    }

    private void matchSellOrder(Order incoming, List<Trade> trades, List<Order> updatedOrders) {
        while (incoming.getRemainingQuantity() > 0 && !bids.isEmpty()) {
            PriceLevel bestBidLevel = bids.firstEntry().getValue();

            boolean crosses = incoming.getOrderType() == OrderType.MARKET
                || incoming.getPrice().compareTo(bestBidLevel.getPrice()) <= 0;
            if (!crosses) break;

            Order resting = bestBidLevel.peek();
            long fillQty = Math.min(incoming.getRemainingQuantity(), resting.getRemainingQuantity());

            trades.add(Trade.builder()
                .id(UUID.randomUUID().toString())
                .symbol(incoming.getSymbol())
                .buyOrderId(resting.getId())
                .sellOrderId(incoming.getId())
                .executedPrice(bestBidLevel.getPrice())
                .executedQty(fillQty)
                .executedAt(Instant.now())
                .build());

            incoming.setRemainingQuantity(incoming.getRemainingQuantity() - fillQty);
            resting.setRemainingQuantity(resting.getRemainingQuantity() - fillQty);
            updateStatus(incoming);
            updateStatus(resting);
            if (!updatedOrders.contains(resting)) updatedOrders.add(resting);

            if (resting.getRemainingQuantity() == 0) bestBidLevel.removeHead();
            if (bestBidLevel.isEmpty()) bids.pollFirstEntry();
        }
    }

    private void updateStatus(Order order) {
        if (order.getRemainingQuantity() == 0)
            order.setStatus(OrderStatus.FILLED);
        else if (order.getRemainingQuantity() < order.getOriginalQuantity())
            order.setStatus(OrderStatus.PARTIALLY_FILLED);
        else
            order.setStatus(OrderStatus.NEW);
    }

        // called by exactly ONE thread — MatchingEngineWorker. No lock needed.
    public MatchResult processOrder(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        List<Order> updatedOrders = new ArrayList<>();

        if (incoming.getSide() == Side.BUY) {
            matchBuyOrder(incoming, trades, updatedOrders);
            if (incoming.getRemainingQuantity() > 0 && incoming.getOrderType() == OrderType.LIMIT) {
                bids.computeIfAbsent(incoming.getPrice(), PriceLevel::new).addOrder(incoming);
            }
        } else {
            matchSellOrder(incoming, trades, updatedOrders);
            if (incoming.getRemainingQuantity() > 0 && incoming.getOrderType() == OrderType.LIMIT) {
                asks.computeIfAbsent(incoming.getPrice(), PriceLevel::new).addOrder(incoming);
            }
        }

        updateStatus(incoming);
        updatedOrders.add(incoming);
        return new MatchResult(incoming, trades, updatedOrders);
    }

     public void cancel(String orderId, Side side, BigDecimal price) {
        TreeMap<BigDecimal, PriceLevel> book = side == Side.BUY ? bids : asks;
        PriceLevel level = book.get(price);
        if (level == null) return;
        level.cancel(orderId);
        if (level.isEmpty()) book.remove(price);
    }

    public Optional<Order> getBestBid() {
        if (bids.isEmpty()) return Optional.empty();
        return Optional.ofNullable(bids.firstEntry().getValue().peek());
    }

    public Optional<Order> getBestAsk() {
        if (asks.isEmpty()) return Optional.empty();
        return Optional.ofNullable(asks.firstEntry().getValue().peek());
    }
}
