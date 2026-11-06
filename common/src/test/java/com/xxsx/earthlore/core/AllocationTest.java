package com.xxsx.earthlore.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Logic half of earth_lore_research_atomic: either everything is paid or nothing is. */
class AllocationTest {
    @Test
    void solvesOverlapGreedyWouldMiss() {
        // slot 0 holds A, slot 1 holds B. Requirement 0 accepts A or B, requirement 1 accepts only A.
        int[] slots = {1, 1};
        int[] reqs = {1, 1};
        boolean[][] accepts = {{true, true}, {true, false}};
        int[][] take = Allocation.solve(slots, reqs, accepts);
        assertNotNull(take);
        assertArrayEquals(new int[]{0, 1}, take[0]);
        assertArrayEquals(new int[]{1, 0}, take[1]);
    }

    @Test
    void refusesWhenShort() {
        int[][] take = Allocation.solve(new int[]{3, 2}, new int[]{6}, new boolean[][]{{true, true}});
        assertNull(take);
    }

    @Test
    void splitsAcrossSlots() {
        int[][] take = Allocation.solve(new int[]{3, 4}, new int[]{6}, new boolean[][]{{true, true}});
        assertNotNull(take);
        assertArrayEquals(new int[]{6}, new int[]{take[0][0] + take[0][1]});
    }
}

