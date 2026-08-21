package com.trading.matching_engine;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.trading.matching_engine.api.OrderRequest;
import com.trading.matching_engine.api.OrderValidator;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;

class OrderValidatorTest {

    private final OrderValidator validator = new OrderValidator();

    private OrderRequest valid() {
        OrderRequest r = new OrderRequest();
        r.setSymbol("AAPL");
        r.setSide(Side.BUY);
        r.setOrderType(OrderType.LIMIT);
        r.setPrice(new BigDecimal("100.00"));
        r.setQuantity(100);
        r.setClientId("client-1");
        r.setClientOrderId("co-1");
        return r;
    }

    private void assertRejected(OrderRequest req) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> validator.validate(req));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void acceptsAWellFormedLimitOrder() {
        assertDoesNotThrow(() -> validator.validate(valid()));
    }

    @Test
    void acceptsAWellFormedMarketOrder() {
        OrderRequest r = valid();
        r.setOrderType(OrderType.MARKET);
        r.setPrice(null);
        assertDoesNotThrow(() -> validator.validate(r));
    }

    @Test
    void rejectsLimitOrderWithoutPrice() {
        OrderRequest r = valid();
        r.setPrice(null);
        assertRejected(r);   // previously NPE'd on the matching thread, after a 202
    }

    @Test
    void rejectsNonPositiveQuantity() {
        OrderRequest r = valid();
        r.setQuantity(0);
        assertRejected(r);

        OrderRequest negative = valid();
        negative.setQuantity(-5);
        assertRejected(negative);
    }

    @Test
    void rejectsNonPositivePrice() {
        OrderRequest r = valid();
        r.setPrice(new BigDecimal("-1.00"));
        assertRejected(r);
    }

    @Test
    void rejectsPriceFinerThanTheTickScale() {
        OrderRequest r = valid();
        r.setPrice(new BigDecimal("100.123456"));
        assertRejected(r);
    }

    @Test
    void rejectsMarketOrderCarryingAPrice() {
        OrderRequest r = valid();
        r.setOrderType(OrderType.MARKET);
        assertRejected(r);
    }

    @Test
    void rejectsMissingIdentifiers() {
        OrderRequest noSymbol = valid();
        noSymbol.setSymbol("  ");
        assertRejected(noSymbol);

        OrderRequest noClient = valid();
        noClient.setClientId(null);
        assertRejected(noClient);

        OrderRequest noClientOrderId = valid();
        noClientOrderId.setClientOrderId(null);
        assertRejected(noClientOrderId);
    }

    @Test
    void rejectsOversizedFields() {
        OrderRequest longSymbol = valid();
        longSymbol.setSymbol("A".repeat(21));
        assertRejected(longSymbol);

        OrderRequest longClientOrderId = valid();
        longClientOrderId.setClientOrderId("c".repeat(51));
        assertRejected(longClientOrderId);
    }
}
