package com.xxsx.earthlore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xxsx.earthlore.api.Grade;
import org.junit.jupiter.api.Test;

/** Logic half of earth_lore_soft_cap, earth_lore_cap_heirloom and earth_lore_hard_lock. */
class GateMathTest {
    @Test
    void softCapsAtCommonUntilLearned() {
        var unknown = GateMath.evaluate(Gate.SOFT, false, false);
        assertEquals(GateMath.Verdict.SOFT_CAPPED, unknown.verdict());
        assertEquals(Grade.COMMON, GateMath.capGrade(Grade.HEIRLOOM, unknown, Grade.HEIRLOOM));
        assertEquals(Grade.MASTERWORK, GateMath.capGrade(Grade.HEIRLOOM, GateMath.evaluate(Gate.SOFT, true, false), Grade.HEIRLOOM));
    }

    @Test
    void capstoneOpensHeirloomButMaterialCapStillApplies() {
        var learned = GateMath.evaluate(Gate.SOFT, true, true);
        assertEquals(Grade.HEIRLOOM, GateMath.capGrade(Grade.HEIRLOOM, learned, Grade.HEIRLOOM));
        assertEquals(Grade.FINE, GateMath.capGrade(Grade.HEIRLOOM, learned, Grade.FINE));
        assertEquals(Grade.MASTERWORK, GateMath.capGrade(Grade.HEIRLOOM, GateMath.evaluate(Gate.ROOT, true, false), Grade.HEIRLOOM));
    }

    @Test
    void hardLocksUntilLearned() {
        assertEquals(GateMath.Verdict.LOCKED, GateMath.evaluate(Gate.HARD, false, true).verdict());
        assertEquals(GateMath.Verdict.OK, GateMath.evaluate(Gate.HARD, true, false).verdict());
        assertEquals(GateMath.Verdict.LOCKED,
                GateMath.evaluate(Gate.SOFT, false, false).and(GateMath.Outcome.LOCKED_OUT).verdict());
    }

    @Test
    void yieldHalvesButNeverZero() {
        assertEquals(1, GateMath.scaledYield(1, false));
        assertEquals(2, GateMath.scaledYield(5, false));
        assertEquals(5, GateMath.scaledYield(5, true));
    }
}

