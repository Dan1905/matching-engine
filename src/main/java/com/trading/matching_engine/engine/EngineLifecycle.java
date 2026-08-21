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
    private final BookRecovery recovery;
    private Thread workerThread;

    public EngineLifecycle(MatchingEngineWorker worker, BookRecovery recovery) {
        this.worker = worker;
        this.recovery = recovery;
    }

    @PostConstruct
    public void start() {
        // Recovery runs on this thread, to completion, before the worker exists — so the
        // book is whole before the first live order can reach it.
        recovery.recover();

        // Platform thread: this is CPU-bound work with no blocking I/O inside it, so a
        // virtual thread would add scheduling indirection for nothing.
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
