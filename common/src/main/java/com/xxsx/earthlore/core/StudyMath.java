package com.xxsx.earthlore.core;

/** Durations from 10 册 A-005 and A-009, scaled by the server speed settings (A-020). */
public final class StudyMath {
    public static final int MAX_TIER = 15;

    private StudyMath() {
    }

    /** 40 + 40 x tier ticks, times the literature form factor, divided by study_speed. */
    public static int studyTicks(int tier, double studySpeed, double formFactor) {
        double base = 40.0 + 40.0 * Math.max(0, Math.min(MAX_TIER, tier));
        double speed = Math.max(0.25, Math.min(4.0, studySpeed));
        double factor = formFactor > 0 ? formFactor : 1.0;
        return Math.max(1, (int) Math.ceil(base * factor / speed));
    }

    public static int tableTicks(int baseTicks, double researchSpeed) {
        if (baseTicks <= 0) {
            return 0;
        }
        double speed = Math.max(0.25, Math.min(4.0, researchSpeed));
        return Math.max(1, (int) Math.ceil(baseTicks / speed));
    }

    public static int copyTicks(int nodes) {
        return 200 * Math.max(1, nodes);
    }

    public static int compileTicks(int nodes) {
        return 100 * Math.max(1, nodes);
    }
}

