package com.trading.matching_engine.domain;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Trade {
   private String id;
    private String buyOrderId;
    private String sellOrderId;
    private String symbol;
    private BigDecimal executedPrice;
    private long executedQty;
    private Instant executedAt;

}
