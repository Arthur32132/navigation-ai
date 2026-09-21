package dev.navigationai.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathNodeBudgetTest {
    @Test
    void resetsOncePerServerTick() {
        PathNodeBudget budget = new PathNodeBudget(2);
        budget.beginTick(10);

        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertFalse(budget.tryConsume());

        budget.beginTick(10);
        assertFalse(budget.tryConsume());
        budget.beginTick(11);
        assertTrue(budget.tryConsume());
    }
}
