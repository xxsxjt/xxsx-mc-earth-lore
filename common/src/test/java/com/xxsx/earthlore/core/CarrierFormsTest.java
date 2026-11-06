package com.xxsx.earthlore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Logic half of earth_lore_carrier_filter. */
class CarrierFormsTest {
    private static final List<CarrierForms.Decl> DECLS = List.of(
            new CarrierForms.Decl("earth_lore:blank_notes", "earth_lore:research_notes", null, null),
            new CarrierForms.Decl("earth_lore:blank_notes", "fe:grimoire", "earth_lore:mag", null),
            new CarrierForms.Decl("earth_lore:blank_notes", "fe:evocation_tome", null, Set.of("fe:mag/evocation")));

    @Test
    void nodeFilterBeatsDisciplineBeatsPlain() {
        assertEquals("fe:evocation_tome", CarrierForms.resolve(DECLS, "earth_lore:blank_notes",
                "earth_lore:mag", List.of("fe:mag/evocation")).orElseThrow());
        assertEquals("fe:grimoire", CarrierForms.resolve(DECLS, "earth_lore:blank_notes",
                "earth_lore:mag", List.of("fe:mag/other")).orElseThrow());
        assertEquals("earth_lore:research_notes", CarrierForms.resolve(DECLS, "earth_lore:blank_notes",
                "earth_lore:geo", List.of("eom:geo/x")).orElseThrow());
    }

    @Test
    void unknownCarrierIsInvalid() {
        assertTrue(CarrierForms.resolve(DECLS, "minecraft:paper", "earth_lore:geo", List.of("eom:geo/x")).isEmpty());
    }
}

