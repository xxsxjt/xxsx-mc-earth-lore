package com.xxsx.earthlore.core;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the written form for a blank carrier (10 册 A-014): declarations whose node filter covers every
 * node win, then a matching discipline filter, then unfiltered declarations. Earlier declarations win
 * ties.
 */
public final class CarrierForms {
    public record Decl(String carrier, String result, String discipline, Set<String> nodes) {
        public Decl {
            nodes = nodes == null ? Set.of() : Set.copyOf(nodes);
        }
    }

    private CarrierForms() {
    }

    public static Optional<String> resolve(List<Decl> decls, String carrier, String discipline,
                                           Collection<String> nodes) {
        Decl byNodes = null;
        Decl byDiscipline = null;
        Decl plain = null;
        for (Decl decl : decls) {
            if (!decl.carrier().equals(carrier)) {
                continue;
            }
            boolean disciplineOk = decl.discipline() == null || decl.discipline().equals(discipline);
            if (!decl.nodes().isEmpty()) {
                if (byNodes == null && disciplineOk && !nodes.isEmpty() && decl.nodes().containsAll(nodes)) {
                    byNodes = decl;
                }
            } else if (decl.discipline() != null) {
                if (byDiscipline == null && disciplineOk) {
                    byDiscipline = decl;
                }
            } else if (plain == null) {
                plain = decl;
            }
        }
        Decl chosen = byNodes != null ? byNodes : byDiscipline != null ? byDiscipline : plain;
        return Optional.ofNullable(chosen).map(Decl::result);
    }
}

