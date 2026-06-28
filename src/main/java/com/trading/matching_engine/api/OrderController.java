package com.trading.matching_engine.api;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trading.matching_engine.domain.Side;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<String> submit(@RequestBody OrderRequest req) {
        String orderId = orderService.submit(req);
        return ResponseEntity.accepted().body(orderId); // 202 — async processing
    }

     @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> cancel(
            @PathVariable String orderId,
            @RequestParam Side side,
            @RequestParam BigDecimal price) {
        orderService.cancel(orderId, side, price);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{orderId}/status")
    public ResponseEntity<String> status(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.getStatus(orderId));
    }

    @GetMapping("/trades/{symbol}")
    public ResponseEntity<List<TradeResponse>> trades(@PathVariable String symbol) {
        return ResponseEntity.ok(orderService.getTradesBySymbol(symbol));
    }
}
