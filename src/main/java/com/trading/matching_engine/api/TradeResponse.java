package com.trading.matching_engine.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.trading.matching_engine.domain.Trade;

import lombok.Builder;
import lombok.Getter;


@Getter
@Builder
public class TradeResponse {
    private String id;
    private String symbol;
    private String buyOrderId;
    private String sellOrderId;
    private BigDecimal executedPrice;
    private long executedQty;
    private Instant executedAt;

    public static TradeResponse from(Trade t) {
        return TradeResponse.builder()
            .id(t.getId())
            .symbol(t.getSymbol())
            .buyOrderId(t.getBuyOrderId())
            .sellOrderId(t.getSellOrderId())
            .executedPrice(t.getExecutedPrice())
            .executedQty(t.getExecutedQty())
            .executedAt(t.getExecutedAt())
            .build();
    }
}
