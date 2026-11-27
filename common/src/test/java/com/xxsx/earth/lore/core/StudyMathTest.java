package com.xxsx.earth.lore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StudyMathTest {
    @Test
    void studyDurationMatchesReducedTierAndCustomFormula() {
        assertEquals(4, StudyMath.studyTicks(0, 1.0, 1.0));
        assertEquals(24, StudyMath.studyTicks(5, 1.0, 1.0));
        assertEquals(64, StudyMath.studyTicks(15, 1.0, 1.0));
        assertEquals(12, StudyMath.studyTicks(5, 2.0, 1.0));
        assertEquals(12, StudyMath.studyTicks(5, 1.0, 0.5));
        assertEquals(120, StudyMath.studyTicks(5, 120, 1.0, 1.0));
    }

    @Test
    void tableDurationsScaleToOneTenth() {
        assertEquals(120, StudyMath.tableTicks(2400, 2.0));
        assertEquals(0, StudyMath.tableTicks(0, 1.0));
        assertEquals(400, StudyMath.copyTicks(2));
        assertEquals(40, StudyMath.tableTicks(StudyMath.copyTicks(2), 1.0));
        assertEquals(300, StudyMath.compileTicks(3));
        assertEquals(30, StudyMath.tableTicks(StudyMath.compileTicks(3), 1.0));
    }
}
