package com.trading.matching_engine.api;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Ticks;

/**
 * Rejects malformed orders at the API boundary, synchronously, with a 400.
 *
 * Anything that gets past here reaches the matching thread, where the client already
 * holds a 202 and an order id — at that point a failure can only be reported
 * asynchronously as a REJECTED status. Cheap validation belongs on this side of the queue.
 */
@Component
public class OrderValidator {
    private static final int MAX_SYMBOL_LENGTH = 20;
    private static final int MAX_CLIENT_ORDER_ID_LENGTH = 50;
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999999999");

    public void validate(OrderRequest req) {
        require(req != null, "request body is required");
        require(isPresent(req.getSymbol()), "symbol is required");
        require(req.getSymbol().length() <= MAX_SYMBOL_LENGTH,
            "symbol must be at most " + MAX_SYMBOL_LENGTH + " characters");
        require(req.getSide() != null, "side is required (BUY or SELL)");
        require(req.getOrderType() != null, "orderType is required (LIMIT or MARKET)");
        require(req.getQuantity() > 0, "quantity must be greater than zero");
        require(isPresent(req.getClientId()), "clientId is required");
        require(isPresent(req.getClientOrderId()), "clientOrderId is required");
        require(req.getClientOrderId().length() <= MAX_CLIENT_ORDER_ID_LENGTH,
            "clientOrderId must be at most " + MAX_CLIENT_ORDER_ID_LENGTH + " characters");

        if (req.getOrderType() == OrderType.LIMIT) {
            require(req.getPrice() != null, "price is required for a LIMIT order");
            require(req.getPrice().signum() > 0, "price must be greater than zero");
            require(req.getPrice().abs().compareTo(MAX_PRICE) <= 0, "price is out of range");
            require(Ticks.isRepresentable(req.getPrice()),
                "price must have at most " + Ticks.SCALE + " decimal places");
        } else {
            require(req.getPrice() == null, "a MARKET order must not carry a price");
        }
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
