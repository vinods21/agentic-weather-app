package com.example.weather.concurrent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Bounded parallel fan-out with an overall deadline (ADR 0013).
 *
 * <p>{@link #map} runs {@code task} for every input on at most {@code maxConcurrency} workers and
 * returns one result per input, in input order. An empty slot means the item did not complete
 * before the deadline. After the deadline (or a failure) no new task is started, the result is a
 * snapshot that late completions can't change, and the running workers are interrupted.
 *
 * <p>The task handles its own expected failures and must return a non-null value. Any throwable
 * that escapes it (or a null result) is a bug: the other workers are cancelled and the original
 * throwable is rethrown on the calling thread. A failure that arrives after {@code map} has
 * returned is logged at ERROR with its class name only and dropped.
 *
 * <p>The caller's MDC is installed on every worker and cleared when the worker ends.
 */
public final class BoundedFanOut implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BoundedFanOut.class);

    private final ExecutorService executor;

    public BoundedFanOut(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** A fan-out whose workers are virtual threads named {@code namePrefix + n} (ADR 0013 §3). */
    public static BoundedFanOut virtualThreads(String namePrefix) {
        return new BoundedFanOut(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(namePrefix, 0).factory()));
    }

    /**
     * Applies {@code task} to every input with at most {@code maxConcurrency} in flight and returns
     * within about {@code timeout}.
     *
     * @throws IllegalArgumentException if {@code maxConcurrency < 1} or {@code timeout} is not positive
     * @throws IllegalStateException if the calling thread is interrupted while waiting (its interrupt
     *         flag is restored)
     * @throws RejectedExecutionException if the executor has been shut down
     */
    public <T, R> List<Optional<R>> map(List<T> inputs, Function<? super T, ? extends R> task,
                                        int maxConcurrency, Duration timeout) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(timeout, "timeout");
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (inputs.isEmpty()) {
            return List.of();
        }
        List<T> items = List.copyOf(inputs);
        Run<T, R> run = new Run<>(items, task, System.nanoTime() + saturatedNanos(timeout));
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        int workers = Math.min(maxConcurrency, items.size());
        List<Future<?>> futures = new ArrayList<>(workers);
        try {
            for (int w = 0; w < workers; w++) {
                futures.add(executor.submit(() -> run.work(mdc)));
            }
        } catch (RejectedExecutionException e) {
            run.stop();
            cancel(futures);
            throw e;
        }
        return run.await(workers, futures);
    }

    /** Shuts the executor down and interrupts running workers. */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static long saturatedNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void cancel(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    /** A worker signal: finished normally ({@code failure == null}) or failed. */
    private record Signal(Throwable failure) {
        static final Signal DONE = new Signal(null);
    }

    /** The shared state of one {@link #map} call. */
    private static final class Run<T, R> {

        private final List<T> inputs;
        private final Function<? super T, ? extends R> task;
        private final long deadline;
        private final AtomicInteger next = new AtomicInteger();
        private final AtomicReferenceArray<R> results;
        private final BlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean stopped;

        Run(List<T> inputs, Function<? super T, ? extends R> task, long deadline) {
            this.inputs = inputs;
            this.task = task;
            this.deadline = deadline;
            this.results = new AtomicReferenceArray<>(inputs.size());
        }

        private boolean deadlinePassed() {
            return System.nanoTime() - deadline >= 0;
        }

        /** Worker loop: take the next index until stopped, past the deadline, interrupted or done. */
        void work(Map<String, String> mdc) {
            if (mdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdc);
            }
            try {
                while (!stopped && !deadlinePassed() && !Thread.currentThread().isInterrupted()) {
                    int index = next.getAndIncrement();
                    if (index >= inputs.size()) {
                        break;
                    }
                    R result;
                    try {
                        result = task.apply(inputs.get(index));
                    } catch (Throwable t) {
                        fail(t);
                        return;
                    }
                    if (result == null) {
                        fail(new NullPointerException("fan-out task returned null"));
                        return;
                    }
                    results.set(index, result);
                }
                signals.add(Signal.DONE);
            } finally {
                MDC.clear();
            }
        }

        private void fail(Throwable failure) {
            lock.lock();
            try {
                if (!stopped) {
                    signals.add(new Signal(failure));
                    return;
                }
            } finally {
                lock.unlock();
            }
            log.error("Fan-out task failed after its deadline exception={}", failure.getClass().getName());
        }

        /** Stops the run; after this, a worker failure is no longer reported to the caller. */
        void stop() {
            lock.lock();
            try {
                stopped = true;
            } finally {
                lock.unlock();
            }
        }

        List<Optional<R>> await(int workers, List<Future<?>> futures) {
            Throwable failure = null;
            boolean interrupted = false;
            int done = 0;
            while (done < workers) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                Signal signal;
                try {
                    signal = signals.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
                if (signal == null) {
                    break;
                }
                if (signal.failure() != null) {
                    failure = signal.failure();
                    break;
                }
                done++;
            }
            stop();
            // A worker may have reported a failure between the last poll and stop().
            for (Signal signal = signals.poll(); signal != null && failure == null; signal = signals.poll()) {
                failure = signal.failure();
            }
            List<Optional<R>> snapshot = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                snapshot.add(Optional.ofNullable(results.get(i)));
            }
            cancel(futures);
            if (interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for fan-out tasks");
            }
            if (failure instanceof RuntimeException e) {
                throw e;
            }
            if (failure instanceof Error e) {
                throw e;
            }
            if (failure != null) {
                throw new IllegalStateException("Fan-out task failed", failure);
            }
            return List.copyOf(snapshot);
        }
    }
}
