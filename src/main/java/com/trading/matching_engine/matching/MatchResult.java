package com.trading.matching_engine.matching;

import java.util.List;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.Trade;

import lombok.Getter;

@Getter
public class MatchResult {
    private final Order incomingOrder;
    private final List<Trade> trades;

    public MatchResult(Order incomingOrder, List<Trade> trades) {
        this.incomingOrder = incomingOrder;
        this.trades = trades;
    }

    public boolean hasMatches() {
        return !trades.isEmpty();
    }

}
