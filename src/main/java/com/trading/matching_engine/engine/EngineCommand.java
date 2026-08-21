package com.trading.matching_engine.engine;

import com.trading.matching_engine.domain.Order;

public sealed interface EngineCommand
    permits EngineCommand.SubmitOrder, EngineCommand.CancelOrder {

    record SubmitOrder(Order order) implements EngineCommand {}

    /** Cancel needs only the id — the engine's index finds the resting order in O(1). */
    record CancelOrder(String orderId) implements EngineCommand {}
}
