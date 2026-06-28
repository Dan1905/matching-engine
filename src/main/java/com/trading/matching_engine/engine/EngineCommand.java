package com.trading.matching_engine.engine;

import java.math.BigDecimal;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.Side;

public sealed interface EngineCommand 
    permits EngineCommand.SubmitOrder, EngineCommand.CancelOrder {

    record SubmitOrder(Order order) implements EngineCommand {}
    record CancelOrder(String orderId, Side side, BigDecimal price) implements EngineCommand {}
}
