package dev.navigationai.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSearchSchedulerTest {
    @Test
    void prioritizesInteractiveSearchesBehindAnAlreadyRunningTask() throws Exception {
        try (PathSearchScheduler scheduler = new PathSearchScheduler(1, 2)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Integer> order = new ArrayList<>();

            Future<?> running = scheduler.submit(0, () -> {
                order.add(0);
                started.countDown();
                await(release);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));

            Future<?> follow = scheduler.submit(10, () -> order.add(10));
            Future<?> direct = scheduler.submit(20, () -> order.add(20));
            release.countDown();

            running.get(1, TimeUnit.SECONDS);
            direct.get(1, TimeUnit.SECONDS);
            follow.get(1, TimeUnit.SECONDS);
            assertEquals(List.of(0, 20, 10), order);
        }
    }

    @Test
    void rejectsSearchesWhenTheBoundedQueueIsFull() throws Exception {
        try (PathSearchScheduler scheduler = new PathSearchScheduler(1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            Future<?> running = scheduler.submit(0, () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Future<?> queued = scheduler.submit(0, () -> { });

            assertThrows(RejectedExecutionException.class, () -> scheduler.submit(0, () -> { }));
            release.countDown();
            running.get(1, TimeUnit.SECONDS);
            queued.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void removesCancelledQueuedSearches() throws Exception {
        try (PathSearchScheduler scheduler = new PathSearchScheduler(1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> running = scheduler.submit(0, () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));

            Future<?> queued = scheduler.submit(0, () -> { });
            scheduler.cancel(queued);
            assertEquals(0, scheduler.queuedTasks());

            Future<?> replacement = scheduler.submit(0, () -> { });
            release.countDown();
            running.get(1, TimeUnit.SECONDS);
            replacement.get(1, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) throw new AssertionError("test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test void olderFollowSearchCannotBeStarvedByLaterDirectRequests() throws Exception {
        try (PathSearchScheduler scheduler = new PathSearchScheduler(1, 16)) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            List<Integer> order = new ArrayList<>();
            Future<?> running = scheduler.submit(0, () -> { started.countDown(); await(release); });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Future<?> follow = scheduler.submit(10, () -> order.add(-1));
            List<Future<?>> direct = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int index = i; direct.add(scheduler.submit(20, () -> order.add(index)));
            }
            release.countDown(); running.get(1, TimeUnit.SECONDS); follow.get(1, TimeUnit.SECONDS);
            for (Future<?> future : direct) future.get(1, TimeUnit.SECONDS);
            assertTrue(order.indexOf(-1) <= 2, order.toString());
        }
    }
}
