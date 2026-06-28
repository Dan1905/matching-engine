package com.trading.matching_engine.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.trading.matching_engine.MatchingEngineApplication;
import com.trading.matching_engine.domain.Order;
import com.trading.matching_engine.domain.OrderStatus;
import com.trading.matching_engine.domain.OrderType;
import com.trading.matching_engine.domain.Side;
import com.trading.matching_engine.engine.EngineCommand;
import com.trading.matching_engine.engine.MatchingEngineWorker;
import com.trading.matching_engine.engine.OrderIngress;
import com.trading.matching_engine.engine.QueueBasedIngress;
import com.trading.matching_engine.persistence.OrderRepository;
import com.trading.matching_engine.persistence.TradeRepository;
import com.trading.matching_engine.redis.OrderStatusCache;

/**
 * Standalone test runner for measuring real matching-engine performance.
 * Separate from OrderGenerator on purpose — generation/submission is one concern,
 * measurement is another. This class owns all timing, polling, and stats printing.
 *
 * Usage:
 *   submit    <count>                          -> producer-side hand-off latency only
 *   e2e       <count>                          -> matched latency plus database verification
 *   concurrent <count> <producerThreads>        -> concurrent submit throughput
 */
public class LatencyTestRunner {

    public static void main(String[] args) throws InterruptedException {
        // DevTools relaunches standalone main classes without their original arguments.
        System.setProperty("spring.devtools.restart.enabled", "false");
        System.setProperty(
            "logging.level.com.trading.matching_engine.persistence.AsyncPersistenceWriter",
            "INFO");

        String mode = args.length > 0 ? args[0].toLowerCase() : "e2e";
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int producerThreads = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        if (count <= 0) throw new IllegalArgumentException("count must be greater than zero");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MatchingEngineApplication.class)
            .web(WebApplicationType.NONE)
            .run()) {

            QueueBasedIngress ingress = context.getBean(QueueBasedIngress.class);
            MatchingEngineWorker worker = context.getBean(MatchingEngineWorker.class);
            OrderStatusCache statusCache = context.getBean(OrderStatusCache.class);
            OrderRepository orderRepository = context.getBean(OrderRepository.class);
            TradeRepository tradeRepository = context.getBean(TradeRepository.class);
            OrderGenerator generator = new OrderGenerator(ingress);

            switch (mode) {
                case "submit" -> {
                    measureSubmitLatency(generator, ingress, count);
                    waitForDrain(worker, 30_000);
                }
                case "e2e" -> {
                    measureEndToEndLatency(
                        generator,
                        ingress,
                        statusCache,
                        worker,
                        orderRepository,
                        tradeRepository,
                        count);
                }
                case "concurrent" -> {
                    measureConcurrentThroughput(generator, producerThreads, count);
                    waitForDrain(worker, 30_000);
                }
                default -> System.out.println("Unknown mode: " + mode + " (use submit | e2e | concurrent)");
            }
        }
    }

    /**
     * Times only ingress.submit() — the cost of handing an order to the queue.
     * NOT matching latency. Useful for proving the hand-off itself is cheap, nothing more.
     */
    private static void measureSubmitLatency(OrderGenerator generator, OrderIngress ingress, int count) {
        long[] latencies = new long[count];
        long wallStart = System.nanoTime();

        for (int i = 0; i < count; i++) {
            Order order = generator.randomOrder(i);

            long start = System.nanoTime();
            boolean accepted = ingress.submit(new EngineCommand.SubmitOrder(order));
            long end = System.nanoTime();

            latencies[i] = end - start;
            if (!accepted) System.out.println("WARNING: queue full, order " + i + " rejected");
        }

        long wallElapsed = System.nanoTime() - wallStart;
        printLatencyStats(latencies, "Submit (producer-side) latency");
        printThroughput(count, wallElapsed);
    }

    /**
     * Real latency for guaranteed-crossing traffic: submit a BUY/SELL pair at the same
     * price, wait until both orders are FILLED, then verify the resulting rows in the
     * database. Orders are submitted as a batch so persistence batching is exercised.
     */
    private static void measureEndToEndLatency(OrderGenerator generator,
                                                OrderIngress ingress,
                                                OrderStatusCache statusCache,
                                                MatchingEngineWorker worker,
                                                OrderRepository orderRepository,
                                                TradeRepository tradeRepository,
                                                int count) throws InterruptedException {
        if (count < 2 || count % 2 != 0) {
            throw new IllegalArgumentException("e2e count must be an even number of at least 2");
        }

        int pairCount = count / 2;
        String symbol = "E2E" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<OrderPair> pairs = new ArrayList<>(pairCount);
        List<String> orderIds = new ArrayList<>(count);
        long batchStart = System.nanoTime();

        for (int pair = 0; pair < pairCount; pair++) {
            BigDecimal price = new BigDecimal("150.00");
            long qty = 100L;
            Order buy = makeCrossingOrder(generator, pair * 2, symbol, price, qty, Side.BUY);
            Order sell = makeCrossingOrder(generator, pair * 2 + 1, symbol, price, qty, Side.SELL);
            long submittedAt = System.nanoTime();

            if (!ingress.submit(new EngineCommand.SubmitOrder(buy))) {
                throw new IllegalStateException("Queue full while submitting BUY order " + buy.getId());
            }
            if (!ingress.submit(new EngineCommand.SubmitOrder(sell))) {
                throw new IllegalStateException("Queue full while submitting SELL order " + sell.getId());
            }
            pairs.add(new OrderPair(buy.getId(), sell.getId(), submittedAt));
            orderIds.add(buy.getId());
            orderIds.add(sell.getId());
        }

        long[] matchLatencies = waitForFilledPairs(statusCache, pairs, orderIds, 30_000);
        long matchingElapsed = System.nanoTime() - batchStart;
        printLatencyStats(matchLatencies, "End-to-end matching latency (per pair)");
        printThroughput(count, matchingElapsed);

        waitForDrain(worker, 30_000);
        int filledOrders = orderRepository.countBySymbolAndStatus(symbol, OrderStatus.FILLED.name());
        int persistedTrades = tradeRepository.countBySymbol(symbol);
        if (filledOrders != count || persistedTrades != pairCount) {
            throw new IllegalStateException(
                "Persistence verification failed for " + symbol + ": expected " + count
                    + " FILLED orders and " + pairCount + " trades, found " + filledOrders
                    + " FILLED orders and " + persistedTrades + " trades");
        }

        double durableMillis = (System.nanoTime() - batchStart) / 1_000_000.0;
        System.out.printf("Database verification: %d FILLED orders, %d trades (%.3f ms total)%n",
            filledOrders, persistedTrades, durableMillis);
    }

    /**
     * Concurrent producer-side throughput only. Confirms the queue itself holds up
     * under concurrent load; does not measure matching speed.
     */
    private static void measureConcurrentThroughput(OrderGenerator generator,
                                                      int producerThreads,
                                                      int ordersPerThread) throws InterruptedException {
        long start = System.nanoTime();
        generator.submitConcurrent(producerThreads, ordersPerThread);
        long end = System.nanoTime();

        int totalOrders = producerThreads * ordersPerThread;
        double totalSeconds = (end - start) / 1_000_000_000.0;
        double throughput = totalOrders / totalSeconds;

        System.out.println("=== Concurrent Submit Load Test ===");
        System.out.println("Producer threads: " + producerThreads);
        System.out.println("Orders per thread: " + ordersPerThread);
        System.out.println("Total orders submitted: " + totalOrders);
        System.out.printf("Wall clock time (submit only): %.3f sec%n", totalSeconds);
        System.out.printf("Submit throughput: %.0f orders/sec%n", throughput);
        System.out.println("NOTE: producer-side hand-off only, not matching throughput.");
    }

    private static void printLatencyStats(long[] latencies, String label) {
        int sampleCount = latencies.length;
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);

        long p50 = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);
        long p99 = percentile(sorted, 0.99);

        System.out.println("=== " + label + " ===");
        System.out.println("Samples: " + sampleCount);
        System.out.printf("p50: %.3f ms%n", p50 / 1_000_000.0);
        System.out.printf("p95: %.3f ms%n", p95 / 1_000_000.0);
        System.out.printf("p99: %.3f ms%n", p99 / 1_000_000.0);
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static void printThroughput(int orderCount, long elapsedNanos) {
        double seconds = elapsedNanos / 1_000_000_000.0;
        System.out.printf("Wall-clock throughput: %.0f orders/sec%n", orderCount / seconds);
    }

    private static void waitForDrain(MatchingEngineWorker worker, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (worker.isIdle()) {
                System.out.println("Engine drained — all orders processed.");
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Timed out waiting for matching and persistence queues to drain");
    }

    private static Order makeCrossingOrder(OrderGenerator generator,
                                           int seed,
                                           String symbol,
                                           BigDecimal price,
                                           long qty,
                                           Side side) {
        Order order = generator.randomOrder(seed);
        order.setSymbol(symbol);
        order.setSide(side);
        order.setOrderType(OrderType.LIMIT);
        order.setPrice(price);
        order.setOriginalQuantity(qty);
        order.setRemainingQuantity(qty);
        order.setStatus(OrderStatus.NEW);
        return order;
    }

    private static long[] waitForFilledPairs(OrderStatusCache statusCache,
                                              List<OrderPair> pairs,
                                              List<String> orderIds,
                                              long timeoutMillis) throws InterruptedException {
        long[] latencies = new long[pairs.size()];
        boolean[] completed = new boolean[pairs.size()];
        int remaining = pairs.size();
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;

        while (remaining > 0 && System.nanoTime() < deadline) {
            Map<String, String> statuses = statusCache.getAll(orderIds);
            long observedAt = System.nanoTime();
            for (int i = 0; i < pairs.size(); i++) {
                if (completed[i]) continue;
                OrderPair pair = pairs.get(i);
                String buyStatus = statuses.get(pair.buyOrderId());
                String sellStatus = statuses.get(pair.sellOrderId());
                if (OrderStatus.FILLED.name().equals(buyStatus)
                    && OrderStatus.FILLED.name().equals(sellStatus)) {
                    latencies[i] = observedAt - pair.submittedAt();
                    completed[i] = true;
                    remaining--;
                }
            }
            if (remaining > 0) Thread.sleep(2);
        }

        if (remaining > 0) {
            throw new IllegalStateException(
                "Timed out waiting for " + remaining + " matched pairs to reach FILLED");
        }
        return latencies;
    }

    private record OrderPair(String buyOrderId, String sellOrderId, long submittedAt) {}
}
