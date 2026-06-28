package com.trading.matching_engine.api;

import java.math.BigDecimal;

import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderRequest {
    private String symbol;
    private Side side;
    private OrderType orderType;
    private BigDecimal price;          // null when orderType == MARKET
    private long quantity;
    private String clientOrderId;
}
