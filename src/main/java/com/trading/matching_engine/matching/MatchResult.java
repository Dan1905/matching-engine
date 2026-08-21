package com.trading.matching_engine.matching;

import java.util.List;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.Trade;

public record MatchResult(Order incomingOrder, List<Trade> trades, List<Order> updatedOrders) {
    public boolean hasMatches() {
        return !trades.isEmpty();
    }
}
