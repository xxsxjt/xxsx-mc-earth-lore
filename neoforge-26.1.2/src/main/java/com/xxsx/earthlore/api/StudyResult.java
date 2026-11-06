package com.xxsx.earthlore.api;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** Outcome of starting to study a piece of literature (10 册 A-009). */
public record StudyResult(boolean started, Optional<Identifier> node, int ticks, String failCode,
                          List<Identifier> missing) {
    public StudyResult {
        missing = List.copyOf(missing);
    }

    public static StudyResult ok(Identifier node, int ticks) {
        return new StudyResult(true, Optional.of(node), ticks, "", List.of());
    }

    public static StudyResult fail(String code, List<Identifier> missing) {
        return new StudyResult(false, Optional.empty(), 0, code, missing);
    }
}

