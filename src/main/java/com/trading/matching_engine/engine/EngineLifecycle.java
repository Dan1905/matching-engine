package com.trading.matching_engine.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class EngineLifecycle {
     private static final Logger log = LoggerFactory.getLogger(EngineLifecycle.class);

    private final MatchingEngineWorker worker;
    private Thread workerThread;

    public EngineLifecycle(MatchingEngineWorker worker) {
        this.worker = worker;
    }

    @PostConstruct
    public void start() {
        // platform thread — this is CPU-bound work, not I/O-bound, so no virtual thread here
        workerThread = Thread.ofPlatform()
            .name("matching-engine-worker")
            .start(worker);
        log.info("Matching engine worker thread started");
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        log.info("Shutting down matching engine worker...");
        worker.stop();
        workerThread.interrupt();
        workerThread.join(5_000);
        log.info("Matching engine worker stopped cleanly");
    }
}
