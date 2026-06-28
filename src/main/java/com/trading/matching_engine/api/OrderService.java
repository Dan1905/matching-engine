package com.trading.matching_engine.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.OrderIngress;
import com.trading.matching_engine.persistence.TradeRepository;
import com.trading.matching_engine.redis.OrderStatusCache;
import com.trading.matching_engine.redis.RateLimiter;

@Service
public class OrderService {
    private final OrderIngress ingress;
    private final TradeRepository tradeRepo;
    private final OrderStatusCache statusCache;
    private final RateLimiter rateLimiter;

    public OrderService(OrderIngress ingress,
                         TradeRepository tradeRepo,
                         OrderStatusCache statusCache,
                         RateLimiter rateLimiter) {
        this.ingress = ingress;
        this.tradeRepo = tradeRepo;
        this.statusCache = statusCache;
        this.rateLimiter = rateLimiter;
    }

        public String submit(OrderRequest req) {
        if (rateLimiter.isLimited(req.getClientOrderId())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded for client: " + req.getClientOrderId());
        }

        String orderId = UUID.randomUUID().toString();

        Order order = Order.builder()
            .id(orderId)
            .symbol(req.getSymbol())
            .side(req.getSide())
            .orderType(req.getOrderType())
            .price(req.getPrice())
            .originalQuantity(req.getQuantity())
            .remainingQuantity(req.getQuantity())
            .status(OrderStatus.NEW)
            .clientOrderId(req.getClientOrderId())
            .createdAt(Instant.now())
            .build();

        boolean accepted = ingress.submit(new EngineCommand.SubmitOrder(order));
        if (!accepted) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Engine queue full — try again shortly");
        }

        statusCache.put(orderId, OrderStatus.NEW.name());
        return orderId;
    }

    public void cancel(String orderId, Side side, BigDecimal price) {
        boolean accepted = ingress.submit(new EngineCommand.CancelOrder(orderId, side, price));
        if (!accepted) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Engine queue full — try again shortly");
        }
        // actual status update now happens in MatchingEngineWorker.handleCancel(),
        // once the cancel command is actually processed by the single worker thread
    }

    public String getStatus(String orderId) {
        return statusCache.get(orderId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Order not found: " + orderId));
    }

    public List<TradeResponse> getTradesBySymbol(String symbol) {
        return tradeRepo.findBySymbol(symbol)
            .stream()
            .map(TradeResponse::from)
            .toList();
    }

}