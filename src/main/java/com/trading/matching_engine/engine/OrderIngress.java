package com.trading.matching_engine.engine;

public interface OrderIngress {
    boolean submit(EngineCommand command);
    EngineCommand take() throws InterruptedException;
    boolean isEmpty();
}
