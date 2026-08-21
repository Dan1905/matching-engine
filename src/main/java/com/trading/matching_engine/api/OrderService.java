package com.trading.matching_engine.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.OrderIngress;
import com.trading.matching_engine.persistence.AsyncPersistenceWriter;
import com.trading.matching_engine.persistence.OrderRepository;
import com.trading.matching_engine.persistence.TradeRepository;
import com.trading.matching_engine.redis.OrderStatusCache;
import com.trading.matching_engine.redis.RateLimiter;

@Service
public class OrderService {
    private final OrderIngress ingress;
    private final TradeRepository tradeRepo;
    private final OrderRepository orderRepo;
    private final OrderStatusCache statusCache;
    private final RateLimiter rateLimiter;
    private final OrderValidator validator;
    private final AsyncPersistenceWriter writer;

    public OrderService(OrderIngress ingress,
                        TradeRepository tradeRepo,
                        OrderRepository orderRepo,
                        OrderStatusCache statusCache,
                        RateLimiter rateLimiter,
                        OrderValidator validator,
                        AsyncPersistenceWriter writer) {
        this.ingress = ingress;
        this.tradeRepo = tradeRepo;
        this.orderRepo = orderRepo;
        this.statusCache = statusCache;
        this.rateLimiter = rateLimiter;
        this.validator = validator;
        this.writer = writer;
    }

    public String submit(OrderRequest req) {
        validator.validate(req);

        if (rateLimiter.isLimited(req.getClientId())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded for client: " + req.getClientId());
        }

        // Shed load before the queues are actually full. The alternative — letting the
        // persistence queue fill — forces the matching thread to block on hand-off.
        if (ingress.isSaturated() || writer.isSaturated()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Engine is saturated — retry shortly");
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

        // Seed the cache BEFORE handing the order over. Writing NEW afterwards races the
        // engine, which may already have published FILLED — and would overwrite it.
        statusCache.put(orderId, OrderStatus.NEW.name());

        if (!ingress.submit(new EngineCommand.SubmitOrder(order))) {
            statusCache.evict(orderId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Engine queue full — try again shortly");
        }
        return orderId;
    }

    /** Cancel by id alone — the client no longer has to tell us the side and price. */
    public void cancel(String orderId) {
        if (!ingress.submit(new EngineCommand.CancelOrder(orderId))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Engine queue full — try again shortly");
        }
    }

    public String getStatus(String orderId) {
        return statusCache.get(orderId)
            .or(() -> orderRepo.findById(orderId).map(o -> o.getStatus().name()))
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Order not found: " + orderId));
    }

    public List<TradeResponse> getTradesBySymbol(String symbol) {
        return tradeRepo.findBySymbol(symbol).stream().map(TradeResponse::from).toList();
    }
}
