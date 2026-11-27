package com.xxsx.earth.lore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphValidatorTest {
    private static final Set<String> DISCIPLINES = Set.of("earth_lore:geo", "earth_lore:xua");
    private static final GraphValidator.DependencyRule ONLY_LORE =
            (owner, other) -> owner.equals(other) || other.equals("earth_lore");

    private static NodeDef node(String id, String discipline, Integer tier, Gate gate, List<Prereq> requires,
                                List<String> unlocks) {
        return new NodeDef(id, discipline, tier, gate, requires, unlocks, false);
    }

    private static NodeDef root(String id) {
        return node(id, "earth_lore:geo", 0, Gate.ROOT, List.of(), List.of());
    }

    @Test
    void acceptsCleanGraph() {
        var result = GraphValidator.validate(List.of(
                root("a:geo/basics"),
                node("a:geo/ore", "earth_lore:geo", 1, Gate.SOFT, List.of(new Prereq.All("a:geo/basics")), List.of("item:a:pick"))
        ), DISCIPLINES, ONLY_LORE);
        assertEquals(2, result.accepted().size());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    void rejectsBadDisciplineAndTier() {
        var result = GraphValidator.validate(List.of(
                node("a:geo/x", "earth_lore:nope", 1, Gate.SOFT, List.of(), List.of()),
                node("a:geo/y", "earth_lore:geo", 16, Gate.SOFT, List.of(), List.of()),
                node("a:geo/z", "earth_lore:geo", 2, Gate.ROOT, List.of(), List.of()),
                node("a:geo/w", "earth_lore:geo", 2, null, List.of(), List.of())
        ), DISCIPLINES, ONLY_LORE);
        assertEquals(FailCodes.BAD_DISCIPLINE, result.rejected().get("a:geo/x"));
        assertEquals(FailCodes.BAD_TIER, result.rejected().get("a:geo/y"));
        assertEquals(FailCodes.BAD_TIER, result.rejected().get("a:geo/z"));
        assertEquals(FailCodes.BAD_TIER, result.rejected().get("a:geo/w"));
    }

    @Test
    void cascadesFromRejectedPrerequisite() {
        var result = GraphValidator.validate(List.of(
                node("a:geo/base", "earth_lore:geo", 1, Gate.SOFT, List.of(), List.of()).withBadReference(),
                node("a:geo/mid", "earth_lore:geo", 2, Gate.SOFT, List.of(new Prereq.All("a:geo/base")), List.of()),
                node("a:geo/top", "earth_lore:geo", 3, Gate.HARD, List.of(new Prereq.All("a:geo/mid")), List.of())
        ), DISCIPLINES, ONLY_LORE);
        assertEquals(FailCodes.BAD_UNLOCK, result.rejected().get("a:geo/base"));
        assertEquals(FailCodes.MISSING_PREREQ, result.rejected().get("a:geo/mid"));
        assertEquals(FailCodes.MISSING_PREREQ, result.rejected().get("a:geo/top"));
        assertTrue(result.accepted().isEmpty());
    }

    @Test
    void rejectsTierOrderAndCrossWithoutAlternative() {
        var result = GraphValidator.validate(List.of(
                node("a:geo/one", "earth_lore:geo", 2, Gate.SOFT, List.of(), List.of()),
                node("a:geo/two", "earth_lore:geo", 2, Gate.SOFT, List.of(new Prereq.All("a:geo/one")), List.of()),
                node("b:geo/foreign", "earth_lore:geo", 1, Gate.SOFT, List.of(), List.of()),
                node("a:geo/cross", "earth_lore:geo", 3, Gate.SOFT, List.of(new Prereq.All("b:geo/foreign")), List.of())
        ), DISCIPLINES, ONLY_LORE);
        assertEquals(FailCodes.TIER_ORDER, result.rejected().get("a:geo/two"));
        assertEquals(FailCodes.CROSS_WITHOUT_ALT, result.rejected().get("a:geo/cross"));
        assertFalse(result.rejected().containsKey("a:geo/one"));
    }

    @Test
    void duplicateUnlockRejectsBoth() {
        var result = GraphValidator.validate(List.of(
                node("a:geo/p", "earth_lore:geo", 1, Gate.SOFT, List.of(), List.of("item:a:thing")),
                node("a:geo/q", "earth_lore:geo", 1, Gate.HARD, List.of(), List.of("item:a:thing"))
        ), DISCIPLINES, ONLY_LORE);
        assertEquals(FailCodes.DUPLICATE_UNLOCK, result.rejected().get("a:geo/p"));
        assertEquals(FailCodes.DUPLICATE_UNLOCK, result.rejected().get("a:geo/q"));
    }

    @Test
    void anyGroupSurvivesWhenOneMemberLoaded() {
        var result = GraphValidator.validate(List.of(
                node("x:xua/alt", "earth_lore:xua", 1, Gate.THEORY, List.of(), List.of()),
                node("x:xua/top", "earth_lore:xua", 2, Gate.HARD,
                        List.of(new Prereq.Any(List.of("b:geo/missing", "x:xua/alt"))), List.of())
        ), DISCIPLINES, ONLY_LORE);
        assertTrue(result.accepted().containsKey("x:xua/top"));
    }

    @Test
    void autoSkipsMissingCompanionModPrerequisitesWithoutBreakingNode() {
        Set<String> loadedMods = Set.of("earth_lore", "x");
        var result = GraphValidator.validate(List.of(
                node("x:xua/root", "earth_lore:xua", 0, Gate.ROOT, List.of(), List.of()),
                node("x:xua/cross_all", "earth_lore:xua", 1, Gate.SOFT,
                        List.of(new Prereq.All("x:xua/root"), new Prereq.All("earth_magic:arcane_basics")), List.of()),
                node("x:xua/cross_any_all_missing", "earth_lore:xua", 1, Gate.SOFT,
                        List.of(new Prereq.Any(List.of("earth_magic:a", "earth_theology:b"))), List.of())
        ), DISCIPLINES, ONLY_LORE, loadedMods::contains);
        assertTrue(result.accepted().containsKey("x:xua/cross_all"));
        assertTrue(result.accepted().containsKey("x:xua/cross_any_all_missing"));
        assertTrue(result.missingCompanionMods().contains("earth_magic"));
        assertTrue(result.missingCompanionMods().contains("earth_theology"));
        assertEquals(1, result.accepted().get("x:xua/cross_all").requires().size());
        assertTrue(result.accepted().get("x:xua/cross_any_all_missing").requires().isEmpty());
    }

    @Test
    void stillRejectsMissingPrereqWhenCompanionModIsActuallyLoaded() {
        Set<String> loadedMods = Set.of("earth_lore", "x", "earth_magic");
        var result = GraphValidator.validate(List.of(
                node("x:xua/typo", "earth_lore:xua", 1, Gate.SOFT,
                        List.of(new Prereq.All("earth_magic:nonexistent_typo")), List.of())
        ), DISCIPLINES, ONLY_LORE, loadedMods::contains);
        assertEquals(FailCodes.MISSING_PREREQ, result.rejected().get("x:xua/typo"));
    }
}
