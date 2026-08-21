package com.trading.matching_engine.persistence;

import com.trading.matching_engine.domain.OrderSnapshot;
import com.trading.matching_engine.domain.Trade;

/**
 * What crosses the matching thread -> writer thread boundary. Immutable values only:
 * the writer must never be handed an object the matching thread is still mutating.
 */
public sealed interface WriteEvent permits WriteEvent.OrderEvent, WriteEvent.TradeEvent, WriteEvent.StatusEvent {
    record OrderEvent(OrderSnapshot order) implements WriteEvent {}
    record TradeEvent(Trade trade) implements WriteEvent {}
    record StatusEvent(String orderId, String status) implements WriteEvent {}
}
