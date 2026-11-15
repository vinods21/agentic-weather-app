# 0013. Bounded parallel fan-out of upstream calls: per-request virtual-thread workers, a monotonic deadline, MDC propagation and cancellation
Date: 2026-11-15 · Status: Accepted · Feature: weather-for-all-favourites

## Context
`GET /api/v1/favourites/weather` has to look up current weather for up to `max-per-client`
(default 20, at most 100) favourites in one request. Up to now every request made at most 2
upstream calls, sequentially on the request thread (ADR 0006). The requirements (A-5, AC-16,
AC-17, NFR "Resources") ask for:
- at most `max-concurrency` (default 4, 1..20) upstream calls in flight per request;
- an overall time budget `timeout` (default 5 s, 1 s..30 s) after which the response is sent,
  with items that are still waiting or in flight reported as failed;
- no upstream call *started* after the deadline, and a late result that cannot change the
  response already sent;
- a bounded executor (no unbounded thread creation per request);
- the request id (MDC, ADR 0003) on every log line a worker writes, e.g. the adapter's
  `Upstream call failed …` WARN (ADR 0006 §5);
- no new dependency, and tests that don't flake on a slow Windows laptop.

This is the first concurrency in the service, so later fan-outs will copy these choices.

## Decision
1. **One small helper, `concurrent.BoundedFanOut`**, owns the mechanics. The domain service
   gives it a list of inputs, a task, a concurrency limit and a timeout, and gets back one
   `Optional` result per input **in input order**. An empty slot means "not completed before
   the deadline". The task must return a non-null value, and it handles its own *expected*
   failures (it turns them into a per-item result value). Any exception that escapes the task
   is a bug (see 5).
2. **Workers, not one thread per item.** Per call the helper starts
   `k = min(maxConcurrency, inputs.size())` workers (0 for an empty list: no thread at all). Each
   worker loops: check stop/deadline/interrupt → take the next index from a shared
   `AtomicInteger` → run the task → store the result in an `AtomicReferenceArray` slot. The
   number of calls in flight per request is therefore ≤ `k` by construction. No semaphore or
   queue sizing is needed.
3. **Virtual threads from the JDK.** Workers run on an executor created once per helper bean:
   `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("fanout-", 0).factory())`.
   - The bound is per request (≤ `max-concurrency` threads). The total is ≤ concurrent requests ×
     `max-concurrency`, and Tomcat's request thread pool bounds the number of concurrent requests.
   - Virtual threads block cheaply on the JDK `HttpClient` and on Lettuce, so no pool has to be
     sized against latency.
   - The `ExecutorService` is **not** registered as a Spring bean. An `Executor` bean would make
     Boot's `applicationTaskExecutor` auto-configuration back off. The helper is the bean, and
     it shuts the executor down (`shutdownNow`) when the context closes.
   - `spring.threads.virtual.enabled` is not changed. Tomcat and the rest of the app keep
     platform threads.
4. **Deadline on the monotonic clock.** `deadline = System.nanoTime() + timeout` is taken when
   the fan-out starts. The service `Clock` bean is not used for this, because it only provides
   timestamps and is fixed in tests. The calling (request) thread waits on a `BlockingQueue` of
   worker signals with `poll(remaining)`, so it wakes exactly when every worker has finished, a
   worker failed, or the deadline passed. At that point, in this order, it:
   1. sets a shared `stopped` flag (workers check it, the deadline and their interrupt flag before
      taking the next index, so **no call starts after the deadline**);
   2. takes a **snapshot** of the result slots, and builds the response only from it, so a late
      result can't change it (a late success may still be written to the cache by the adapter);
   3. calls `Future.cancel(true)` on every worker, which interrupts in-flight calls (best effort:
      the JDK `HttpClient` aborts an interrupted `send`, and Spring reports it as an I/O error, so
      the adapter's existing translation applies). A call that ignores the interrupt still ends
      within its own connect + read timeouts (ADR 0006 §2), so at most `k` lingering virtual
      threads per request.
5. **Fail fast on bugs.** A worker that catches a `RuntimeException` or `Error` from the task
   signals it. The request thread then stops and cancels the other workers as in 4, and rethrows
   the original throwable unchanged, so `ApiExceptionHandler` gives `500 INTERNAL_ERROR` exactly
   as for a sequential bug. A failure that arrives after the request thread has returned is
   logged once at ERROR with the exception class only (no message, no stack trace) and dropped.
   If the request thread is interrupted while waiting, it stops and cancels as in 4, restores
   its interrupt flag and throws `IllegalStateException`.
6. **MDC propagation.** The helper captures `MDC.getCopyOfContextMap()` on the calling thread.
   Each worker installs it (or clears the MDC if it was null) before its first task, and calls
   `MDC.clear()` in `finally`. Log lines from the adapter and the cache on a worker thread
   therefore carry `[requestId=…]` through the existing log pattern. No Micrometer
   context-propagation library and no `TaskDecorator` is needed.
7. **No database work on workers.** The caller loads everything it needs from the database
   *before* the fan-out. No transaction or connection is held while the fan-out runs (ADR 0008 §3).
8. **No retries, no circuit breaker** (ADR 0006 §3). The deadline is the only new resilience
   mechanism.

## Consequences
- The response time of the endpoint is bounded by `timeout` plus the database read and
  serialisation, independent of the per-call read timeout.
- Upstream concurrency per request is bounded, but not globally. N concurrent dashboard requests
  can have N × `max-concurrency` calls in flight. A global limit or rate limiting is out of scope
  and would need its own ADR.
- After a deadline, up to `k` virtual threads per request can still be finishing their (already
  started) calls for at most connect + read timeout. Their `Upstream call failed` or `Cache
  operation failed` WARN lines (an interrupted Lettuce call counts as `reason=unavailable`) can
  appear after the access-log line, still with the request id.
- Java 21 virtual threads can pin their carrier inside `synchronized` blocks. That costs
  throughput, not correctness. If profiling shows pinning in Lettuce or logging, the executor
  can be swapped for a bounded platform pool behind the same helper without an API change.
- Unit tests can drive the helper deterministically with latches (see the design's test
  strategy). Only upper time bounds depend on the machine, and they are generous.

## Alternatives considered
- **`parallelStream()` / the common `ForkJoinPool`**: no deadline or per-request limit, shared with
  unrelated work, blocking I/O starves it, and no MDC propagation.
- **`CompletableFuture.supplyAsync` + `orTimeout`**: `cancel(true)` on a `CompletableFuture`
  does not interrupt the running thread, so in-flight calls keep running and nothing stops new
  ones from starting. A limit would still need a semaphore.
- **One virtual thread per item plus a `Semaphore(maxConcurrency)`**: correct and short, but it
  creates up to `max-per-client` threads per request. Threads waiting for a permit must
  re-check the deadline after acquiring it, and "never more than k in flight" depends on
  permit discipline instead of structure.
- **A shared bounded platform `ThreadPoolTaskExecutor`**: bounds global threads, but its size
  either caps throughput across requests (requests queue behind each other and time out) or has
  to be tuned against upstream latency. It also needs a rejection policy and a `TaskDecorator`
  for MDC. Registering it as a bean would also replace Boot's `applicationTaskExecutor`.
- **Spring `@Async`**: proxy-based, no per-call deadline or limit, and it needs an executor bean
  (same back-off issue).
- **Reactor / WebClient**: a new dependency and a different programming model for one endpoint.
