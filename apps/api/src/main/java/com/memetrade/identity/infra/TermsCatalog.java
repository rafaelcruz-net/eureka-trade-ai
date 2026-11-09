package com.memetrade.identity.infra;

import com.memetrade.identity.domain.legal.ActiveLegalDocuments;
import com.memetrade.identity.domain.legal.IgnoredVersion;
import com.memetrade.identity.domain.legal.InvalidLegalCatalogException;
import com.memetrade.identity.domain.legal.LegalActivation;
import com.memetrade.identity.domain.legal.LegalSettings;
import com.memetrade.identity.domain.legal.LegalVersion;
import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsDocument;
import com.memetrade.identity.infra.LegalCatalogReader.LoadedVersion;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Reads the versioned legal catalog ONCE at start-up and keeps it in memory (NFR3.5, NFR10.3). The folders are read
 * by {@link LegalCatalogReader}; {@link LegalActivation} chooses the effective current version with the injectable
 * clock (BR1.1): only a reviewed (or legacy) version, effective now and publishable when required, is ever current.
 * Fails fast, with a clear message, on an invalid catalog or without an activatable version: the api does not start
 * without terms (NFR7.1). The invitation and the Account screens see only the effective current version and the
 * versions of the history (BR1.16).
 */
public class TermsCatalog implements CurrentTerms {

    private static final Logger log = LoggerFactory.getLogger(TermsCatalog.class);

    private final ActiveLegalDocuments active;
    private final Map<String, TermsDocument> historyDocuments;

    public TermsCatalog(
            ResourcePatternResolver resources, TermsProperties properties, LegalSettings settings, Clock clock) {
        List<LoadedVersion> loaded = read(resources, properties);
        Map<String, TermsDocument> documents = new HashMap<>();
        for (LoadedVersion item : loaded) {
            if (documents.putIfAbsent(item.legal().version(), item.document()) != null) {
                throw invalid("the version " + item.legal().version() + " appears more than once");
            }
        }
        this.active = activate(loaded, settings, clock);
        Map<String, TermsDocument> history = new HashMap<>();
        for (LegalVersion version : active.history()) {
            history.put(version.version(), documents.get(version.version()));
        }
        this.historyDocuments = Map.copyOf(history);
        for (IgnoredVersion ignored : active.ignored()) {
            log.warn("Legal version {} ignored: {}", ignored.version(), ignored.reason());
        }
        log.info(
                "Legal documents loaded: current version {}, {} version(s), publishable={}",
                active.current().version(),
                loaded.size(),
                active.publishable());
    }

    /** The effective current version (BR1.1); never an ignored one. */
    @Override
    public TermsDocument current() {
        return historyDocuments.get(active.current().version());
    }

    /** A version of the history only: an ignored, future or later version is never found (BR1.16). */
    @Override
    public Optional<TermsDocument> find(String version) {
        return Optional.ofNullable(version == null ? null : historyDocuments.get(version));
    }

    /** The result of the activation, served by L1. */
    public ActiveLegalDocuments active() {
        return active;
    }

    private static List<LoadedVersion> read(ResourcePatternResolver resources, TermsProperties properties) {
        try {
            return LegalCatalogReader.read(resources, properties.location());
        } catch (InvalidLegalCatalogException problem) {
            // The whole problem goes into the start-up message; it carries no stack worth keeping (NFR7.1).
            throw invalid(problem.getMessage());
        }
    }

    private static ActiveLegalDocuments activate(List<LoadedVersion> loaded, LegalSettings settings, Clock clock) {
        try {
            return LegalActivation.activate(
                    loaded.stream().map(LoadedVersion::legal).toList(), settings, clock.instant());
        } catch (InvalidLegalCatalogException problem) {
            throw invalid(problem.getMessage());
        }
    }

    private static IllegalStateException invalid(String problem) {
        return new IllegalStateException(
                "The api cannot start without valid terms: " + problem + " (memetrade.terms.location, BR1.1)");
    }
}
