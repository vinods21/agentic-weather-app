package com.example.weather.support;

import com.example.weather.support.FakeOpenMeteo.RecordedRequest;
import com.example.weather.support.FakeOpenMeteo.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A thread-safe {@link FakeOpenMeteo} responder for {@code fake.stub(Route.CURRENT, responder)}
 * that records concurrency (design "Test support"). For every request it:
 * <ol>
 *   <li>increments the in-flight counter and records the maximum;</li>
 *   <li>if a gate of size {@code n} is set, the first {@code n} arrivals each count the gate down
 *       and wait (capped) until all {@code n} have arrived: a correct parallel caller opens it at
 *       once, a serial one times out and records max 1;</li>
 *   <li>runs the optional {@code before} hook (e.g. wait for another request to complete);</li>
 *   <li>sleeps the per-request delay (the simulated upstream latency, keyed on the request, e.g.
 *       its {@code latitude}, so behaviour never depends on scheduling);</li>
 *   <li>builds the response, then decrements the in-flight counter <em>before</em> the fake writes
 *       the response, so a caller can only start its next call after the decrement.</li>
 * </ol>
 * The returned response always has zero extra delay.
 */
public final class InFlightResponder implements Function<RecordedRequest, Response> {

    private static final Duration GATE_CAP = Duration.ofSeconds(5);

    private final Function<RecordedRequest, Duration> delay;
    private volatile Function<RecordedRequest, Response> response = r -> Response.ok(FakeOpenMeteo.CURRENT_AC6);
    private volatile BiConsumer<InFlightResponder, RecordedRequest> before = (self, r) -> { };
    private volatile CountDownLatch gate;
    private final AtomicInteger gateTickets = new AtomicInteger();
    private volatile boolean gateTimedOut;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger started = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();
    private final List<String> completedLatitudes = new ArrayList<>();
    private final Object monitor = new Object();

    public InFlightResponder(Function<RecordedRequest, Duration> delay) {
        this.delay = delay;
    }

    /** Every request delayed by {@code d}. */
    public static InFlightResponder fixedDelay(Duration d) {
        return new InFlightResponder(r -> d);
    }

    /** The first {@code n} arrivals wait for each other (capped at 5 s) before their delay. */
    public InFlightResponder gate(int n) {
        this.gate = new CountDownLatch(n);
        this.gateTickets.set(n);
        return this;
    }

    /** The response to send (default {@code 200 CURRENT_AC6}); its own delay is ignored. */
    public InFlightResponder respond(Function<RecordedRequest, Response> response) {
        this.response = response;
        return this;
    }

    /** A hook run before the delay, e.g. to wait for another request with {@link #awaitCompleted}. */
    public InFlightResponder before(BiConsumer<InFlightResponder, RecordedRequest> hook) {
        this.before = hook;
        return this;
    }

    @Override
    public Response apply(RecordedRequest request) {
        started.incrementAndGet();
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            CountDownLatch g = gate;
            if (g != null && gateTickets.getAndDecrement() > 0) {
                g.countDown();
                if (!g.await(GATE_CAP.toMillis(), TimeUnit.MILLISECONDS)) {
                    gateTimedOut = true;
                }
            }
            before.accept(this, request);
            Duration d = delay.apply(request);
            if (d != null && !d.isZero()) {
                Thread.sleep(d.toMillis());
            }
            Response r = response.apply(request);
            return new Response(r.status(), r.body(), Duration.ZERO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(500, "{\"error\":true,\"reason\":\"interrupted\"}");
        } finally {
            inFlight.decrementAndGet();
            synchronized (monitor) {
                completed.incrementAndGet();
                completedLatitudes.add(latitude(request));
                monitor.notifyAll();
            }
        }
    }

    private static String latitude(RecordedRequest request) {
        List<String> values = request.params().getOrDefault("latitude", List.of());
        return values.size() == 1 ? values.get(0) : "?";
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int started() {
        return started.get();
    }

    public int completed() {
        return completed.get();
    }

    /** True if a gate was set and not every gated arrival came within the cap. */
    public boolean gateTimedOut() {
        return gateTimedOut;
    }

    /** {@code latitude} parameters in completion order. */
    public List<String> completedLatitudes() {
        synchronized (monitor) {
            return List.copyOf(completedLatitudes);
        }
    }

    /** Waits until a request with this {@code latitude} has completed; false on timeout. */
    public boolean awaitCompleted(String latitude, Duration timeout) {
        return awaitCondition(() -> completedLatitudes.contains(latitude), timeout);
    }

    /** Waits until every started request has completed; false on timeout. */
    public boolean awaitIdle(Duration timeout) {
        return awaitCondition(() -> completed.get() == started.get() && inFlight.get() == 0, timeout);
    }

    private boolean awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }
}
