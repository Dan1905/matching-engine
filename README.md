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

That buys determinism and structural correctness. It does **not**, as measured
here, buy raw throughput — a plain `synchronized` block beats this design by
about 3× on a ~125 ns critical section, because a `BlockingQueue` hand-off costs
more than the contention it removes. See
[Measured Results](#throughput-single-writer-vs-locks--the-result-that-did-not-go-my-way)
for the numbers and what to do about it.

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
  │  validated synchronously (400), rate-limited via Redis,
  │  load-shed when either queue is saturated (503)
  ▼
OrderIngress (ArrayBlockingQueue<EngineCommand>)
  │  thread-safe hand-off — the only synchronized boundary in the system
  ▼
MatchingEngineWorker          ← single dedicated platform thread
  │  takes commands one at a time, in order
  ▼
MatchingEngine
  │  one OrderBook per symbol, each a TreeMap<priceTicks, PriceLevel>
  │  price-time priority; zero locks
  ▼
results: updated orders + trades, copied to immutable snapshots
  │
  ▼
AsyncPersistenceWriter (separate queue, separate virtual thread)
  │  batches DB writes AND Redis status updates — all I/O off the matching thread
  ├──► PostgreSQL (batched JDBC upserts, retried, dead-lettered on failure)
  └──► Redis (pipelined status writes, read-through cache for clients)
```

Two independent queues solve two independent problems:

- **Ingress queue**: many producers → one matching thread. Protects the order book.
- **Persistence queue**: matching thread → one writer thread. Keeps all I/O
  (database, Redis) off the matching hot path entirely.

The matching thread's job, every iteration, is: take a command, mutate the
book, hand off a few cheap in-memory events, repeat. It never blocks on a
network call.

## The Book

One `OrderBook` per symbol — orders in different symbols can never match each
other, and each symbol's book is independent, which is also the natural shard
boundary if this ever needs more than one matching thread.

Inside a book:

- **`TreeMap<Long, PriceLevel>`**, keyed by integer price ticks, bids descending
  and asks ascending. Ticks, not `BigDecimal`: a decimal key means a heap
  allocation and a pointer-chasing `compareTo` on every tree navigation, where a
  `long` is a register compare. `BigDecimal` survives only at the API and
  database boundaries (`Ticks`, scale 4, matching `NUMERIC(18,4)`).
- **`PriceLevel`** is an intrusive doubly-linked FIFO, giving time priority
  within a price. Append, peek and unlink are all O(1).
- **An id → node index** across all books, so **cancel is O(1) and needs only the
  order id** — no side, no price, no scan of the level. Cancels dominate message
  volume in real markets, so a linear scan there is the wrong asymptotics.
- **Trade ids come from a per-run prefix plus a counter**, not
  `UUID.randomUUID()`, which draws from a shared `SecureRandom` — a lock and a
  few microseconds on the one thread that can afford neither.

## Correctness Across the Thread Boundary

The single-writer rule protects the book, but it does not protect what leaves
the book. A resting order keeps being mutated by the matching thread after it
has been handed to the persistence queue — later fills, then maybe a cancel.
Publishing the live object would let the writer thread read fields that are
still being written, and record a state that never existed.

So the matching thread publishes **immutable `OrderSnapshot`s**, never the live
`Order`. `Order` itself is final and immutable apart from quantity and status,
and those two fields never leave the matching thread except through a snapshot.

## Durability

Delivery to Postgres is **at-least-once**, not best-effort:

- **Nothing is dropped on a full queue.** The API sheds load with a 503 once
  either queue passes 80% depth. If the persistence queue somehow still fills,
  the matching thread blocks on the hand-off rather than discarding an
  execution record — a latency spike is recoverable, a lost fill is not.
- **A failed flush is retried** with exponential backoff. Both statements are
  idempotent (`ON CONFLICT DO UPDATE` / `DO NOTHING`), so replaying a batch after
  a partial failure is safe.
- **What still cannot be written is dead-lettered** to `data/failed-writes.tsv`
  for replay, not logged and discarded.
- **Redis failures never fail a durable write.** The cache is rebuildable from
  Postgres, so a cache outage degrades status reads to a database lookup and
  nothing more.

**Recovery**: the book is in-memory, so on startup `BookRecovery` replays every
`NEW`/`PARTIALLY_FILLED` order out of Postgres, oldest first, and places it
directly into its book — never through `processOrder`, which would invent trades
that never happened. Without this, a restart leaves clients holding orders the
engine has no record of.

**The remaining gap, stated honestly**: persistence is asynchronous, so a hard
crash can lose the events still sitting in the persistence queue. Closing that
window properly means journaling the input command stream *before* applying it,
the way LMAX does, with batched fsyncs to keep it off the latency path. That is
not built here.

## Failure Handling at the API

Validation happens synchronously, before the 202. Anything that gets past the
API reaches the matching thread, where the client already holds an order id and
a failure can only be reported asynchronously — so a null price on a `LIMIT`
order is a `400`, not an exception swallowed by the worker loop.

Orders that fail *at* the engine get a terminal status the client can poll:
an unfillable `MARKET` order becomes `REJECTED` rather than silently
disappearing, a partially filled one has its remainder `CANCELLED`
(immediate-or-cancel), and an unexpected matching failure marks the order
`REJECTED` and persists that.

The status cache is seeded with `NEW` **before** the order is handed to the
engine. Writing it afterwards races the engine, which may already have
published `FILLED` — and would overwrite it with a stale `NEW` for the length
of the TTL.

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

Re-measured after the rewrite in this section's successor, against Postgres 16
and Redis 7 in Docker: **12,236 / 12,384 / 13,099 / 14,425 orders/sec** across
four runs of 50,000 orders, each reconciling 50,000 `FILLED` rows and 25,000
trades in the database before the run was allowed to pass. That path is
matching plus durable persistence; it does not include the HTTP layer, since
`LatencyTestRunner` submits straight to the ingress queue.

Reproduce it with:

```bash
docker run -d --name me-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=matching_engine -p 5432:5432 postgres:16-alpine
```
```bash
docker run -d --name me-redis -p 6379:6379 redis:7-alpine
```
```bash
java -cp "target/classes:target/test-classes:$(cat target/test-cp.txt)" com.trading.matching_engine.simulation.LatencyTestRunner e2e 50000
```

## Measured Results

Apple M4 (4 performance + 6 efficiency cores), JDK 21, JMH 1.37, 2 forks.
Matching only — no Postgres, no Redis, no HTTP.

### Latency per order (`Mode.SampleTime`, 1.15M+ samples)

| | p50 | p90 | p99 | p99.9 | p99.99 |
|---|---|---|---|---|---|
| `processOrder` (the algorithm) | **0.125 µs** | 0.292 µs | 0.708 µs | 6.08 µs | 41.1 µs |
| submit → matched (through the queue) | **1.75 µs** | 2.04 µs | 5.66 µs | 27.2 µs | 87.6 µs |

The tail past p99.9 is GC and scheduler noise, not the book — max samples reach
tens of milliseconds, which is what you would expect from a JVM with no GC
tuning and no thread pinning.

### Throughput: single-writer vs. locks — the result that did not go my way

Same `processOrder`, same order stream, same producer thread count. The only
difference is how access is serialized.

| Producers | Single-writer queue | `synchronized` | `ReentrantReadWriteLock` |
|---|---|---|---|
| 4 | 869k ± 90k ops/s | **3,129k ± 221k** | 3,076k ± 53k |
| 8 | 1,225k ± 273k ops/s | **3,091k ± 578k** | 2,840k ± 491k |

**The lock wins on throughput, by roughly 2.5–3.5×.** That is the opposite of
what this architecture is usually assumed to buy, and it is worth being precise
about why.

The critical section here is ~125 ns. Serializing it with a monitor costs an
uncontended CAS plus, under contention, a park/unpark — and crucially the work
is done *by the calling thread*, on a core that already has the order in cache.
Handing the same work to a dedicated thread through an `ArrayBlockingQueue`
costs a lock on the queue, a condition signal, and a cache-line transfer of the
order to another core, per order. When the protected work is only ~125 ns, that
hand-off is more expensive than the contention it avoids.

This is exactly why LMAX built the Disruptor rather than using a
`BlockingQueue`: a lock-free ring buffer with batched claim/publish amortizes
the hand-off across many orders instead of paying it per order. **Replacing the
ingress queue with a ring buffer is the honest next step for this project**, and
until that is done, "single-writer" here should be read as a claim about
determinism and correctness, not raw throughput.

What the single-writer model does still buy, and what the numbers do support:

- **Producer latency is decoupled from book depth.** A caller pays ~1.75 µs to
  hand off, whether the incoming order rests immediately or sweeps twenty price
  levels. Under a lock, a caller can be stuck behind another thread's deep sweep.
- **No lock on the book at all**, so correctness is structural — there is no
  path where a future contributor forgets to take one.
- **A clean shard boundary.** The book is already partitioned per symbol, so the
  way to scale past one core is more matching threads, not a faster lock.


### How the benchmarks are kept honest

- The lock benchmark and the single-writer benchmark run **the same
  `MatchingEngine.processOrder()`, on the same generated order stream, from the
  same number of producer threads.** The only difference is whether access is
  serialized by a lock or by handing the work to a dedicated thread, so the delta
  is attributable to the concurrency model.
- Orders are generated by a **per-thread xorshift**, not `Math.random()`, which is
  globally synchronized and would otherwise be measured instead of the book.
- Sides alternate across a narrow price band so most orders **cross**. A book
  that only grows turns a throughput benchmark into a GC measurement.
- The single-writer benchmark **caps the in-flight window**, so a producer cannot
  run ahead of the matching thread. Without that, the number measured is
  `queue.offer()`, not matching.
- Latency is reported as **percentiles from `Mode.SampleTime`**. A throughput
  average cannot support a claim about per-order latency.

## Stack

Java 21 · Spring Boot 3.2 · PostgreSQL · Redis · JMH · Docker

## Running

```bash
docker compose up -d                 # Postgres + Redis
psql -f src/main/resources/schema.sql
mvn spring-boot:run
```

Benchmarks and simulation harnesses live under `src/test` and are never
packaged into the production jar:

```bash
mvn -q test-compile org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/test-cp.txt
java -cp "target/classes:target/test-classes:$(cat target/test-cp.txt)" \
  com.trading.matching_engine.benchmark.LatencyBenchmark
```

Swap in `SingleWriterBenchmark` or `BaselineLockBenchmark` for the throughput
comparison, or `simulation.LatencyTestRunner e2e 1000` for a full-pipeline run
against real Postgres and Redis. (JMH forks its own JVMs, which is why the
benchmarks need a real classpath rather than `exec:java`.)

## What This Is Not

This is **not** lock-free in the technical sense — that term applies to a
CAS-based ring buffer with no internal blocking, which isn't built here. The
ingress queue (`ArrayBlockingQueue`) has its own internal locking; what's
removed is *application-level* lock contention on the order book itself. This
is correctly called a single-writer-thread architecture.

Also absent, and deliberately so: no journaling of the input command stream (see
Durability above), no self-trade prevention, no iceberg or stop orders, no
market-data feed, and one matching thread for all symbols rather than a shard
per symbol — the book is already partitioned by symbol, so that is the next step
rather than a rewrite.
