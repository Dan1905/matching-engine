package com.trading.matching_engine.persistence;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.trading.matching_engine.domain.OrderSnapshot;
import com.trading.matching_engine.domain.Trade;

/**
 * Last resort for write events the database has refused after every retry. Appending
 * them here means a Postgres outage costs replay work, not data — the previous
 * behaviour silently discarded the whole 500-event batch and logged a line about it.
 *
 * Tab-separated, one event per line, fields in the order the repositories bind them,
 * so a replay is a COPY away. Written only from the persistence writer thread.
 */
@Component
public class DeadLetterLog {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterLog.class);
    private static final char SEP = '\t';

    private final Path file;
    private final AtomicLong written = new AtomicLong();

    public DeadLetterLog(@Value("${engine.persistence.dead-letter-file:data/failed-writes.tsv}") String path) {
        this.file = Paths.get(path);
    }

    public void append(List<WriteEvent> events) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (WriteEvent e : events) {
                    out.write(format(e));
                    out.newLine();
                }
                out.flush();
            }
            long total = written.addAndGet(events.size());
            log.error("Wrote {} undeliverable events to {} (total {}). Replay required.",
                events.size(), file.toAbsolutePath(), total);
        } catch (IOException io) {
            log.error("DATA LOSS: could not write {} events to the dead-letter log at {}",
                events.size(), file.toAbsolutePath(), io);
        }
    }

    private String format(WriteEvent event) {
        StringBuilder sb = new StringBuilder(160);
        switch (event) {
            case WriteEvent.OrderEvent oe -> {
                OrderSnapshot o = oe.order();
                sb.append("ORDER").append(SEP)
                  .append(o.id()).append(SEP)
                  .append(o.symbol()).append(SEP)
                  .append(o.side()).append(SEP)
                  .append(o.orderType()).append(SEP)
                  .append(o.price()).append(SEP)
                  .append(o.originalQuantity()).append(SEP)
                  .append(o.remainingQuantity()).append(SEP)
                  .append(o.status()).append(SEP)
                  .append(o.clientOrderId()).append(SEP)
                  .append(o.createdAt());
            }
            case WriteEvent.TradeEvent te -> {
                Trade t = te.trade();
                sb.append("TRADE").append(SEP)
                  .append(t.getId()).append(SEP)
                  .append(t.getBuyOrderId()).append(SEP)
                  .append(t.getSellOrderId()).append(SEP)
                  .append(t.getSymbol()).append(SEP)
                  .append(t.getExecutedPrice()).append(SEP)
                  .append(t.getExecutedQty()).append(SEP)
                  .append(t.getExecutedAt());
            }
            case WriteEvent.StatusEvent se ->
                sb.append("STATUS").append(SEP).append(se.orderId()).append(SEP).append(se.status());
        }
        return sb.toString();
    }
}
