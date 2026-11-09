package com.memetrade.identity.domain.legal;

import com.memetrade.identity.api.RetentionItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One immutable version of the legal documents (entities.md LegalVersion), read from the catalog at start-up. A new
 * text is a new version, never an edit. {@code privacy} is {@code null} and {@code retention} empty only for a legacy
 * version (BR1.3, BR1.15).
 */
public record LegalVersion(
        String version,
        Instant effectiveAt,
        boolean markedCurrent,
        boolean legacy,
        LocalDate legalReviewedAt,
        String legalReviewedBy,
        LegalText terms,
        LegalText privacy,
        LegalText riskNotice,
        List<RetentionItem> retention) {

    public LegalVersion {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(riskNotice, "riskNotice");
        retention = List.copyOf(retention);
    }

    /** Reviewed means a recorded legal review (date and reviewer), or a legacy version (BR1.1 (a)). */
    public boolean reviewed() {
        return legacy || (legalReviewedAt != null && legalReviewedBy != null);
    }

    /** Sections of the three texts together, for the limit of 100 per version (performance-design.md Q3). */
    public int sectionCount() {
        return terms.sections().size()
                + (privacy == null ? 0 : privacy.sections().size())
                + riskNotice.sections().size();
    }
}
