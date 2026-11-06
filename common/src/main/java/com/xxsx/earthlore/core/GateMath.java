package com.xxsx.earthlore.core;

import com.xxsx.earthlore.api.Grade;

/** Gate semantics of 10 册 A-010 as plain functions. */
public final class GateMath {
    public enum Verdict { OK, SOFT_CAPPED, LOCKED }

    /** @param cap highest grade the content may reach with the current knowledge */
    public record Outcome(Verdict verdict, Grade cap) {
        public static final Outcome FREE = new Outcome(Verdict.OK, Grade.HEIRLOOM);
        public static final Outcome LOCKED_OUT = new Outcome(Verdict.LOCKED, Grade.CRUDE);

        public Outcome and(Outcome other) {
            Verdict worse = verdict.ordinal() >= other.verdict.ordinal() ? verdict : other.verdict;
            return new Outcome(worse, cap.min(other.cap));
        }
    }

    private GateMath() {
    }

    /**
     * @param gate      gate of the node that gates the content
     * @param gateKnown the deciding player (or machine owner) knows that node
     * @param capKnown  they also know the capstone of the same discipline in the same mod
     */
    public static Outcome evaluate(Gate gate, boolean gateKnown, boolean capKnown) {
        Grade learned = capKnown ? Grade.HEIRLOOM : Grade.MASTERWORK;
        return switch (gate) {
            case ROOT -> new Outcome(Verdict.OK, learned);
            case THEORY, CAP -> Outcome.FREE;
            case SOFT -> gateKnown ? new Outcome(Verdict.OK, learned) : new Outcome(Verdict.SOFT_CAPPED, Grade.COMMON);
            case HARD -> gateKnown ? new Outcome(Verdict.OK, learned) : Outcome.LOCKED_OUT;
        };
    }

    /** Knowledge cap and the material's own cap both apply; the lower one wins. */
    public static Grade capGrade(Grade wanted, Outcome outcome, Grade materialCap) {
        return wanted.min(outcome.cap()).min(materialCap);
    }

    /** Yield multiplier for yield: unlocks (rule 4): x0.5 rounded down but at least one item. */
    public static int scaledYield(int count, boolean known) {
        if (known || count <= 0) {
            return count;
        }
        return Math.max(1, count / 2);
    }
}

