package com.memetrade.identity.domain.legal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Activation of the legal catalog at start-up (BR1.1, BR1.6, BR1.9; F1 of functional-spec.md). A version is
 * ACTIVATABLE when (a) it is reviewed or legacy, (b) its effective date is not after {@code now} (injectable clock) and
 * (c) it is publishable when publishing is required, legacy versions being exempt from (c). The marked version is the
 * current one when activatable; otherwise the current one is the activatable version with the most recent effective
 * date BEFORE the marked one (ordered by date, never by name). Without such a version the api does not start.
 */
public final class LegalActivation {

    public static final String NOT_REVIEWED = "not legally reviewed";
    public static final String FUTURE = "effective in the future";
    public static final String NOT_PUBLISHABLE = "not publishable (missing deployment fields)";

    /** Newest first; equal dates fall back to the version name only to stay deterministic. */
    private static final Comparator<LegalVersion> NEWEST_FIRST = Comparator.comparing(LegalVersion::effectiveAt)
            .thenComparing(LegalVersion::version)
            .reversed();

    private LegalActivation() {}

    public static ActiveLegalDocuments activate(List<LegalVersion> versions, LegalSettings settings, Instant now) {
        LegalVersion marked = marked(versions);
        boolean publishable = settings.deployment().complete();
        Map<LegalVersion, String> reasons = new LinkedHashMap<>();
        for (LegalVersion version : versions) {
            reason(version, settings.requirePublishable() && !publishable, now)
                    .ifPresent(reason -> reasons.put(version, reason));
        }
        LegalVersion current = reasons.containsKey(marked) ? fallback(versions, reasons, marked) : marked;
        List<LegalVersion> history = versions.stream()
                .filter(version -> !reasons.containsKey(version))
                .filter(version -> !version.effectiveAt().isAfter(current.effectiveAt()))
                .sorted(NEWEST_FIRST)
                .toList();
        List<IgnoredVersion> ignored = reasons.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(NEWEST_FIRST))
                .map(entry -> new IgnoredVersion(entry.getKey().version(), entry.getValue()))
                .toList();
        return new ActiveLegalDocuments(current, publishable, settings.deployment(), history, ignored);
    }

    private static LegalVersion marked(List<LegalVersion> versions) {
        List<LegalVersion> marked =
                versions.stream().filter(LegalVersion::markedCurrent).toList();
        if (marked.size() != 1) {
            throw new InvalidLegalCatalogException(
                    "exactly one version must have current=true, found " + marked.size());
        }
        return marked.getFirst();
    }

    private static Optional<String> reason(LegalVersion version, boolean publishingMissing, Instant now) {
        if (!version.reviewed()) {
            return Optional.of(NOT_REVIEWED);
        }
        if (version.effectiveAt().isAfter(now)) {
            return Optional.of(FUTURE);
        }
        if (publishingMissing && !version.legacy()) {
            return Optional.of(NOT_PUBLISHABLE);
        }
        return Optional.empty();
    }

    private static LegalVersion fallback(
            List<LegalVersion> versions, Map<LegalVersion, String> reasons, LegalVersion marked) {
        return versions.stream()
                .filter(version -> !reasons.containsKey(version))
                .filter(version -> version.effectiveAt().isBefore(marked.effectiveAt()))
                .min(NEWEST_FIRST)
                .orElseThrow(() -> new InvalidLegalCatalogException("no activatable version ("
                        + describe(reasons) + "); a reviewed or legacy version effective before "
                        + marked.version() + " is needed (BR1.1)"));
    }

    private static String describe(Map<LegalVersion, String> reasons) {
        List<String> parts = new ArrayList<>();
        reasons.forEach((version, reason) -> parts.add(version.version() + ": " + reason));
        return String.join("; ", parts);
    }
}
