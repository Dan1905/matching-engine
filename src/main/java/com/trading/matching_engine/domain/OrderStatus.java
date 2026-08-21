package com.trading.matching_engine.domain;

public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    /** Never entered the book: failed validation at the engine, or an unfillable market order. */
    REJECTED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}
