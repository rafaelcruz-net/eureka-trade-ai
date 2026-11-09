package com.memetrade.identity.domain.legal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of the activation, computed once at start-up (entities.md ActiveLegalDocuments): the effective current
 * version, whether it is publishable, the deployment fields, the history (current and earlier activatable versions,
 * newest first, BR1.9) and the versions ignored with their reason.
 */
public record ActiveLegalDocuments(
        LegalVersion current,
        boolean publishable,
        DeploymentFields deployment,
        List<LegalVersion> history,
        List<IgnoredVersion> ignored) {

    public ActiveLegalDocuments {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(deployment, "deployment");
        history = List.copyOf(history);
        ignored = List.copyOf(ignored);
    }

    /** A version of the history; an ignored, future or later version is never found (BR1.10, BR1.16). */
    public Optional<LegalVersion> find(String version) {
        if (version == null) {
            return Optional.empty();
        }
        return history.stream().filter(item -> item.version().equals(version)).findFirst();
    }
}
