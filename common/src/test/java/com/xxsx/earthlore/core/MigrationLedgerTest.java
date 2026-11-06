package com.xxsx.earthlore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Logic half of earth_lore_migration_idempotent. */
class MigrationLedgerTest {
    @Test
    void runsOnceAndOnlyAdds() {
        var first = MigrationLedger.run(Set.of("x:xua/old"), Set.of(), List.of("xuanhuan_earth:arcana_v1"),
                id -> Set.of("x:xua/a", "x:xua/b"), false);
        assertEquals(Set.of("x:xua/old", "x:xua/a", "x:xua/b"), first.learned());
        assertEquals(Set.of("xuanhuan_earth:arcana_v1"), first.migrated());
        var second = MigrationLedger.run(first.learned(), first.migrated(), List.of("xuanhuan_earth:arcana_v1"),
                id -> Set.of("x:xua/c"), false);
        assertEquals(first.learned(), second.learned());
        assertTrue(second.added().isEmpty());
        var forced = MigrationLedger.run(first.learned(), first.migrated(), List.of("xuanhuan_earth:arcana_v1"),
                id -> Set.of("x:xua/a"), true);
        assertEquals(first.learned(), forced.learned());
    }

    @Test
    void absentMigratorIsNotRecorded() {
        var outcome = MigrationLedger.run(Set.of(), Set.of(), List.of(), id -> Set.of("x"), false);
        assertTrue(outcome.migrated().isEmpty());
    }
}

