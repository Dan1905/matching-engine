package com.trading.matching_engine.matching;

import java.util.List;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.Trade;

import lombok.Getter;

@Getter
public class MatchResult {
    private final Order incomingOrder;
    private final List<Trade> trades;
    private final List<Order> updatedOrders;

    public MatchResult(Order incomingOrder, List<Trade> trades, List<Order> updatedOrders) {
        this.incomingOrder = incomingOrder;
        this.trades = trades;
        this.updatedOrders = updatedOrders;
    }

    public boolean hasMatches() {
        return !trades.isEmpty();
    }

}
