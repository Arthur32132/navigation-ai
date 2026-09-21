package dev.navigationai.runtime;

import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded priority executor for immutable path searches.
 * Higher priorities run first; requests with the same priority remain FIFO.
 */
final class PathSearchScheduler implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final int queueCapacity;
    private final AtomicLong sequence = new AtomicLong();

    PathSearchScheduler(int workers, int queueCapacity) {
        if (workers < 1) throw new IllegalArgumentException("workers must be positive");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        this.queueCapacity = queueCapacity;
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new BoundedPriorityQueue(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "navigation-ai-path");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    Future<?> submit(int priority, Runnable action) throws RejectedExecutionException {
        PrioritizedTask task = new PrioritizedTask(priority, sequence.getAndIncrement(), action);
        executor.execute(task);
        return task;
    }

    boolean cancel(Future<?> future) {
        if (future == null) return false;
        boolean cancelled = future.cancel(true);
        if (future instanceof Runnable runnable) executor.remove(runnable);
        return cancelled;
    }

    int queuedTasks() { return executor.getQueue().size(); }
    int activeTasks() { return executor.getActiveCount(); }

    /** Defer low-priority background work before the bounded queue starts rejecting it. */
    boolean shouldDefer(int priority) {
        return priority <= 10 && executor.getQueue().size() >= Math.max(1, (queueCapacity * 3) / 4);
    }

    @Override public void close() { executor.shutdownNow(); }

    private static final class PrioritizedTask extends FutureTask<Void> implements Comparable<Runnable> {
        private final int priority;
        private final long sequence;

        private PrioritizedTask(int priority, long sequence, Runnable action) {
            super(action, null);
            this.priority = priority;
            this.sequence = sequence;
        }

        @Override public int compareTo(Runnable other) {
            if (!(other instanceof PrioritizedTask task)) return 0;
            int priorityOrder = Integer.compare(task.priority, priority);
            return priorityOrder != 0
                    ? priorityOrder
                    : Long.compare(sequence, task.sequence);
        }
    }

    private static final class BoundedPriorityQueue extends PriorityBlockingQueue<Runnable> {
        private final int capacity;

        private BoundedPriorityQueue(int capacity) {
            super(Math.max(1, capacity));
            this.capacity = capacity;
        }

        @Override public synchronized boolean offer(Runnable runnable) {
            if (size() >= capacity) return false;
            return super.offer(runnable);
        }

        @Override public synchronized int remainingCapacity() {
            return Math.max(0, capacity - size());
        }
    }
}
