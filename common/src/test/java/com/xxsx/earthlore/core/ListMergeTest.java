package com.xxsx.earthlore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Logic half of earth_lore_teaching_merge. */
class ListMergeTest {
    record Entry(String node, int price) {
    }

    @Test
    void concatenatesAndKeepsFirstDuplicate() {
        var merged = ListMerge.concatDistinct(
                List.of(new Entry("eom:med/first_aid", 8)),
                List.of(new Entry("life:med/anatomy", 12), new Entry("eom:med/first_aid", 99)),
                Entry::node);
        assertEquals(List.of(new Entry("eom:med/first_aid", 8), new Entry("life:med/anatomy", 12)), merged);
    }
}

