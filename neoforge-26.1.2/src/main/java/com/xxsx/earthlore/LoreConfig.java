package com.xxsx.earthlore;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 10 册 A-020. Server values are read through safe getters so GameTests and early calls never throw. */
public final class LoreConfig {
    public enum Scope { PLAYER, TEAM, SERVER }

    public enum TeamSourceOption { AUTO, FTB_TEAMS, SCOREBOARD }

    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.EnumValue<Scope> KNOWLEDGE_SCOPE;
    public static final ModConfigSpec.EnumValue<TeamSourceOption> TEAM_SOURCE;
    public static final ModConfigSpec.DoubleValue RESEARCH_SPEED;
    public static final ModConfigSpec.DoubleValue STUDY_SPEED;
    public static final ModConfigSpec.BooleanValue ALLOW_TEACHING;
    public static final ModConfigSpec.BooleanValue JEI_SHOW_LOCKED;

    /** Test hooks: GameTests switch scope and team source without touching the config file. */
    public static volatile Scope scopeOverride;
    public static volatile TeamSourceOption teamSourceOverride;

    static {
        ModConfigSpec.Builder server = new ModConfigSpec.Builder();
        KNOWLEDGE_SCOPE = server
                .comment("Whose knowledge counts for prerequisites, gates, readouts and yields: PLAYER, TEAM or SERVER.",
                        "Whether a node is already learned always looks at the player's own record.")
                .defineEnum("knowledge_scope", Scope.PLAYER);
        TEAM_SOURCE = server
                .comment("Where TEAM scope reads teams from. AUTO uses FTB Teams when it is installed and vanilla",
                        "scoreboard teams otherwise; FTB_TEAMS falls back to PLAYER scope when FTB Teams is missing.")
                .defineEnum("team_source", TeamSourceOption.AUTO);
        RESEARCH_SPEED = server.comment("Research table speed multiplier.")
                .defineInRange("research_speed", 1.0, 0.25, 4.0);
        STUDY_SPEED = server.comment("Study (reading) speed multiplier.")
                .defineInRange("study_speed", 1.0, 0.25, 4.0);
        ALLOW_TEACHING = server.comment("Villagers and merchants sell research notes listed in earth_lore:teaching data maps.")
                .define("allow_teaching", true);
        SERVER_SPEC = server.build();

        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        JEI_SHOW_LOCKED = client.comment("Show lock marks and gate tooltips on gated recipes in JEI.")
                .define("jei_show_locked", true);
        CLIENT_SPEC = client.build();
    }

    private LoreConfig() {
    }

    public static Scope scope() {
        Scope override = scopeOverride;
        if (override != null) {
            return override;
        }
        try {
            return KNOWLEDGE_SCOPE.get();
        } catch (RuntimeException e) {
            return Scope.PLAYER;
        }
    }

    public static TeamSourceOption teamSource() {
        TeamSourceOption override = teamSourceOverride;
        if (override != null) {
            return override;
        }
        try {
            return TEAM_SOURCE.get();
        } catch (RuntimeException e) {
            return TeamSourceOption.AUTO;
        }
    }

    public static double researchSpeed() {
        try {
            return RESEARCH_SPEED.get();
        } catch (RuntimeException e) {
            return 1.0;
        }
    }

    public static double studySpeed() {
        try {
            return STUDY_SPEED.get();
        } catch (RuntimeException e) {
            return 1.0;
        }
    }

    public static boolean allowTeaching() {
        try {
            return ALLOW_TEACHING.get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean jeiShowLocked() {
        try {
            return JEI_SHOW_LOCKED.get();
        } catch (RuntimeException e) {
            return true;
        }
    }
}

