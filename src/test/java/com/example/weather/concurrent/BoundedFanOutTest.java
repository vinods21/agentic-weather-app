package com.example.weather.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit test for {@link BoundedFanOut}: driven with latches only, no sleep-based assertions. The only
 * time-dependent assertions are generous upper bounds. No Spring, no Docker.
 */
class BoundedFanOutTest {

    private static final Duration LONG = Duration.ofSeconds(30);
    private static final long CAP_SECONDS = 5;

    private final BoundedFanOut fanOut = BoundedFanOut.virtualThreads("fanout-test-");
    private final ExecutorService caller = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        fanOut.close();
        caller.shutdownNow();
        MDC.clear();
    }

    private static List<Integer> range(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(CAP_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("latch not released within " + CAP_SECONDS + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("AC-2: results are in input order even when tasks complete in reverse order")
    void resultsInInputOrder() {
        int n = 5;
        List<CountDownLatch> finished = IntStream.range(0, n).mapToObj(i -> new CountDownLatch(1)).toList();

        List<Optional<String>> results = fanOut.map(range(n), i -> {
            if (i < n - 1) {
                await(finished.get(i + 1));
            }
            finished.get(i).countDown();
            return "r" + i;
        }, n, LONG);

        assertThat(results).containsExactly(
                Optional.of("r0"), Optional.of("r1"), Optional.of("r2"), Optional.of("r3"), Optional.of("r4"));
    }

    @Test
    @DisplayName("AC-16: at most maxConcurrency tasks are in flight, on exactly that many worker threads")
    void concurrencyIsBounded() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        CountDownLatch threeInFlight = new CountDownLatch(3);
        CountDownLatch gate = new CountDownLatch(1);
        Set<String> threads = ConcurrentHashMap.newKeySet();

        Future<List<Optional<Integer>>> call = caller.submit(() -> fanOut.map(range(10), i -> {
            threads.add(Thread.currentThread().getName());
            max.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            threeInFlight.countDown();
            await(gate);
            inFlight.decrementAndGet();
            return i;
        }, 3, LONG));

        await(threeInFlight);
        assertThat(inFlight.get()).isEqualTo(3);
        gate.countDown();
        List<Optional<Integer>> results = call.get(CAP_SECONDS, TimeUnit.SECONDS);

        assertThat(results).containsExactlyElementsOf(range(10).stream().map(Optional::of).toList());
        assertThat(max.get()).isEqualTo(3);
        assertThat(threads).hasSize(3).allMatch(name -> name.startsWith("fanout-test-"));
    }

    @Test
    @DisplayName("AC-17: at the deadline the call returns, the unfinished slot is empty and the task is interrupted")
    void deadlineReturnsSnapshotAndInterrupts() {
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        long start = System.nanoTime();
        List<Optional<String>> results = fanOut.map(List.of("blocked"), s -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return "late";
        }, 1, Duration.ofMillis(200));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(results).containsExactly(Optional.empty());
        assertThat(elapsedMs).isLessThan(2_000);
        await(interrupted);
        assertThat(results).containsExactly(Optional.empty());
    }

    @Test
    @DisplayName("AC-17: no task is started after the deadline")
    void noStartAfterDeadline() throws Exception {
        AtomicInteger started = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch interrupted = new CountDownLatch(1);

        List<Optional<Integer>> results = fanOut.map(range(3), i -> {
            started.incrementAndGet();
            worker.set(Thread.currentThread());
            if (i == 0) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            }
            return i;
        }, 1, Duration.ofMillis(200));

        await(interrupted);
        worker.get().join(Duration.ofSeconds(CAP_SECONDS));
        assertThat(worker.get().isAlive()).isFalse();
        assertThat(started.get()).isEqualTo(1);
        assertThat(results).containsExactly(Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("AC-15: a task failure is rethrown as the same instance and the other workers are interrupted")
    void failFast() {
        IllegalStateException bug = new IllegalStateException("bug");
        CountDownLatch blockedStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        long start = System.nanoTime();
        assertThatThrownBy(() -> fanOut.map(List.of("fails", "blocks"), s -> {
            if (s.equals("fails")) {
                await(blockedStarted);
                throw bug;
            }
            blockedStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return s;
        }, 2, LONG)).isSameAs(bug);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).isLessThan(CAP_SECONDS * 1_000);
        await(interrupted);
    }

    @Test
    @DisplayName("AC-15: an Error is rethrown unchanged and a null result is a bug")
    void errorsAndNullResults() {
        AssertionError error = new AssertionError("boom");
        assertThatThrownBy(() -> fanOut.map(List.of(1), i -> {
            throw error;
        }, 1, LONG)).isSameAs(error);
        assertThatThrownBy(() -> fanOut.map(List.of(1), i -> null, 1, LONG))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("AC-11: workers run with the caller's MDC, and it is cleared afterwards")
    void mdcIsPropagatedAndCleared() {
        MDC.put("requestId", "x");
        List<Optional<String>> seen = fanOut.map(range(4), i -> String.valueOf(MDC.get("requestId")), 2, LONG);
        assertThat(seen).containsOnly(Optional.of("x"));

        MDC.clear();
        List<Optional<String>> fresh = fanOut.map(List.of(1), i -> String.valueOf(MDC.get("requestId")), 1, LONG);
        assertThat(fresh).containsExactly(Optional.of("null"));
    }

    @Test
    @DisplayName("AC-11: a worker's MDC is cleared when it ends, so a pooled thread does not leak it")
    void mdcClearedOnPooledThread() {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try (BoundedFanOut pooled = new BoundedFanOut(single)) {
            MDC.put("requestId", "y");
            assertThat(pooled.map(List.of(1), i -> String.valueOf(MDC.get("requestId")), 1, LONG))
                    .containsExactly(Optional.of("y"));
            MDC.clear();
            assertThat(pooled.map(List.of(1), i -> String.valueOf(MDC.get("requestId")), 1, LONG))
                    .containsExactly(Optional.of("null"));
        }
    }

    @Test
    @DisplayName("AC-4: an empty input returns [] and submits nothing")
    void emptyInputSubmitsNothing() {
        CountingExecutor counting = new CountingExecutor();
        try (BoundedFanOut counted = new BoundedFanOut(counting)) {
            assertThat(counted.map(List.<Integer>of(), i -> i, 4, LONG)).isEmpty();
            assertThat(counting.submitted.get()).isZero();

            counted.map(range(10), i -> i, 4, LONG);
            assertThat(counting.submitted.get()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("NFR: invalid arguments are rejected")
    void invalidArguments() {
        Function<Integer, Integer> identity = i -> i;
        assertThatThrownBy(() -> fanOut.map(List.of(1), identity, 0, LONG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fanOut.map(List.of(1), identity, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fanOut.map(List.of(1), identity, 1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fanOut.map(List.of(1), identity, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("NFR: an interrupted caller gets IllegalStateException with its interrupt flag restored")
    void interruptedCaller() {
        CountDownLatch release = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> fanOut.map(List.of(1), i -> {
                await(release);
                return i;
            }, 1, LONG)).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("NFR: after close, map is rejected")
    void closedRejects() {
        fanOut.close();
        assertThatThrownBy(() -> fanOut.map(List.of(1), i -> i, 1, LONG))
                .isInstanceOf(RejectedExecutionException.class);
    }

    /** Counts submitted workers; runs them on virtual threads. */
    private static final class CountingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger submitted = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            submitted.incrementAndGet();
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<>(delegate.shutdownNow());
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
