package com.xxsx.earthlore.core;

/** Study and research-table durations (scaled to 1/10 for responsive gameplay, with per-node override support). */
public final class StudyMath {
    public static final int MAX_TIER = 15;
    public static final double GLOBAL_TIME_SCALE = 0.1;

    private StudyMath() {
    }

    public static int studyTicks(int tier, double studySpeed, double formFactor) {
        return studyTicks(tier, 0, studySpeed, formFactor);
    }

    /**
     * When {@code customTicks > 0}, uses that explicit per-node duration directly (allowing special/major
     * knowledge nodes to set bespoke study times); otherwise uses 1/10 of the legacy tier curve.
     */
    public static int studyTicks(int tier, int customTicks, double studySpeed, double formFactor) {
        double base = customTicks > 0
                ? customTicks
                : (40 + 40 * Math.max(0, Math.min(MAX_TIER, tier))) * GLOBAL_TIME_SCALE;
        double speed = Math.max(0.25, Math.min(4.0, studySpeed));
        double factor = formFactor > 0 ? formFactor : 1.0;
        return Math.max(1, (int) Math.ceil(base * factor / speed));
    }

    public static int tableTicks(int baseTicks, double researchSpeed) {
        if (baseTicks <= 0) {
            return 0;
        }
        double speed = Math.max(0.25, Math.min(4.0, researchSpeed));
        return Math.max(1, (int) Math.ceil((baseTicks * GLOBAL_TIME_SCALE) / speed));
    }

    public static int copyTicks(int nodes) {
        return 200 * Math.max(1, nodes);
    }

    public static int compileTicks(int nodes) {
        return 100 * Math.max(1, nodes);
    }
}
