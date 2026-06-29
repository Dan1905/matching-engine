# Matching Engine

A single-writer-thread order matching engine built to explore how real exchanges
achieve high throughput without lock contention. Inspired by LMAX Exchange's
architecture and the "mechanical sympathy" principle — work with the hardware
instead of fighting it with locks.

## The Core Idea

A matching engine is almost entirely writes. Every incoming order mutates the
order book. The conventional fix for "many threads, one shared structure" is a
lock. But `ReentrantReadWriteLock`'s main benefit — concurrent readers — barely
applies here, so you pay lock overhead without getting much benefit from it.

Instead of making the lock fast, this removes the need for one. **Exactly one
thread ever touches the order book.** Every other thread that wants to submit
an order hands it off through a queue. Correctness is structural, not enforced
at runtime by `synchronized` or `Lock`.

```
Many threads (HTTP handlers)
        │
        ▼
  Bounded Queue            ← the ONLY place concurrency is handled
        │
        ▼
  ONE dedicated thread     ← the only thread that ever touches the order book
        │
        ▼
  MatchingEngine           ← zero locks, zero synchronization primitives
```

## Architecture

```
Client
  │  POST /api/orders
  ▼
OrderController → OrderService
  │  rate-limited via Redis, builds domain Order
  ▼
OrderIngress (ArrayBlockingQueue<EngineCommand>)
  │  thread-safe hand-off — the only synchronized boundary in the system
  ▼
MatchingEngineWorker          ← single dedicated platform thread
  │  takes commands one at a time, in order
  ▼
MatchingEngine
  │  TreeMap<Price, PriceLevel> bids/asks, price-time priority matching
  │  zero locks — correctness guaranteed by single-thread access, not runtime checks
  ▼
results: updated orders + trades
  │
  ▼
AsyncPersistenceWriter (separate queue, separate virtual thread)
  │  batches DB writes AND Redis status updates — all I/O off the matching thread
  ├──► PostgreSQL (batched JDBC inserts)
  └──► Redis (pipelined status writes, read-through cache for clients)
```

Two independent queues solve two independent problems:

- **Ingress queue**: many producers → one matching thread. Protects the order book.
- **Persistence queue**: matching thread → one writer thread. Keeps all I/O
  (database, Redis) off the matching hot path entirely.

The matching thread's job, every iteration, is: take a command, mutate the
book, hand off three cheap in-memory events, repeat. It never blocks on a
network call.

## Why a Platform Thread, Not a Virtual Thread, for Matching

Java 21 virtual threads help I/O-bound work — they free the carrier thread
while blocked. The matching worker is CPU-bound, tight-loop work with no
blocking I/O inside it. It uses a dedicated platform thread. The persistence
writer, which *does* block on database and Redis I/O, correctly uses a virtual
thread.

## What Got Measured, Not Assumed

Early version measured 2,200 orders/sec end-to-end. Profiling found the
matching thread was making synchronous Redis writes — every matched pair cost
real network round trips on the one thread that should never block. Moving
status writes into the same batched, pipelined persistence queue as the
database writes brought it to 8,500 orders/sec, no change to the matching
algorithm itself.

This is the actual lesson: the bottleneck wasn't the data structure or the
concurrency model, it was an I/O call that didn't belong on the hot path. Real
performance work is about finding which thing is actually slow, not guessing.

## Stack

Java 21 · Spring Boot 3.2 · PostgreSQL · Redis · JMH · Docker

## What This Is Not

This is **not** lock-free in the technical sense — that term applies to a
CAS-based ring buffer with no internal blocking, which isn't built here. The
ingress queue (`ArrayBlockingQueue`) has its own internal locking; what's
removed is *application-level* lock contention on the order book itself. This
is correctly called a single-writer-thread architecture.
