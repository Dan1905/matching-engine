package com.trading.matching_engine.persistence;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.Trade;

public sealed interface WriteEvent permits WriteEvent.OrderEvent, WriteEvent.TradeEvent, WriteEvent.StatusEvent {
    record OrderEvent(Order order) implements WriteEvent {}
    record TradeEvent(Trade trade) implements WriteEvent {}
    record StatusEvent(String orderId, String status) implements WriteEvent {}
}