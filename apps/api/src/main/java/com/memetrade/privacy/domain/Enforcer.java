package com.memetrade.privacy.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Who applies the period of a row (entities.md Enforcer): a purge of the code (PURGE, by its id), the account itself
 * (GUARD, only for "while the account exists") or a Unit still to come (PLANNED, only through the pending list).
 */
public record Enforcer(Kind kind, Optional<String> purgeId, Optional<String> plannedUnit) {

    public enum Kind {
        PURGE,
        GUARD,
        PLANNED
    }

    public Enforcer {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(purgeId, "purgeId");
        Objects.requireNonNull(plannedUnit, "plannedUnit");
        if ((kind == Kind.PURGE) != purgeId.isPresent() || (kind == Kind.PLANNED) != plannedUnit.isPresent()) {
            throw new IllegalArgumentException("PURGE names its purge, PLANNED its Unit, GUARD neither");
        }
    }

    public static Enforcer purge(String purgeId) {
        return new Enforcer(Kind.PURGE, Optional.of(purgeId), Optional.empty());
    }

    public static Enforcer guard() {
        return new Enforcer(Kind.GUARD, Optional.empty(), Optional.empty());
    }

    public static Enforcer planned(String unit) {
        return new Enforcer(Kind.PLANNED, Optional.empty(), Optional.of(unit));
    }
}
