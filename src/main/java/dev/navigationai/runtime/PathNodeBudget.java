package dev.navigationai.runtime;

import java.util.concurrent.atomic.AtomicReference;

/** Shared, reset-per-tick node budget for all path searches on one server. */
final class PathNodeBudget {
    private final int maxNodesPerTick;
    private final AtomicReference<BudgetState> state;
    private volatile int tick = Integer.MIN_VALUE;

    PathNodeBudget(int maxNodesPerTick) {
        if (maxNodesPerTick < 1) throw new IllegalArgumentException("maxNodesPerTick must be positive");
        this.maxNodesPerTick = maxNodesPerTick;
        this.state = new AtomicReference<>(new BudgetState(Integer.MIN_VALUE, maxNodesPerTick));
    }

    void beginTick(int tick) {
        if (this.tick != tick) {
            state.set(new BudgetState(tick, maxNodesPerTick));
            this.tick = tick;
        }
    }

    boolean tryConsume() {
        while (true) {
            BudgetState current = state.get();
            if (current.remaining() <= 0) return false;
            BudgetState next = new BudgetState(current.tick(), current.remaining() - 1);
            if (state.compareAndSet(current, next)) return true;
        }
    }

    int remaining() { return state.get().remaining(); }

    private record BudgetState(int tick, int remaining) { }
}
