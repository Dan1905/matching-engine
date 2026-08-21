package com.trading.matching_engine.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An immutable copy of an Order's state at one instant.
 *
 * The matching thread keeps mutating a resting Order after it has been handed to the
 * persistence queue (later fills, then a cancel). Publishing the live object would let
 * the writer thread read fields that are still being written — an unsynchronized read,
 * and a row that records a state which never existed. Snapshots are what cross the
 * thread boundary; the mutable Order never leaves the matching thread.
 */
public record OrderSnapshot(
    String id,
    String symbol,
    Side side,
    OrderType orderType,
    BigDecimal price,
    long originalQuantity,
    long remainingQuantity,
    OrderStatus status,
    String clientOrderId,
    Instant createdAt
) {}
