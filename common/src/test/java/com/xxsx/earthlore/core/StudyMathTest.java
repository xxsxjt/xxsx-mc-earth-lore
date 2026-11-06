package com.xxsx.earthlore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StudyMathTest {
    @Test
    void studyTimeFollowsTier() {
        assertEquals(40, StudyMath.studyTicks(0, 1.0, 1.0));
        assertEquals(240, StudyMath.studyTicks(5, 1.0, 1.0));
        assertEquals(640, StudyMath.studyTicks(15, 1.0, 1.0));
        assertEquals(120, StudyMath.studyTicks(5, 2.0, 1.0));
        assertEquals(120, StudyMath.studyTicks(5, 1.0, 0.5));
    }

    @Test
    void tableTimeScalesWithSpeed() {
        assertEquals(1200, StudyMath.tableTicks(2400, 2.0));
        assertEquals(0, StudyMath.tableTicks(0, 1.0));
        assertEquals(400, StudyMath.copyTicks(2));
        assertEquals(300, StudyMath.compileTicks(3));
    }
}

