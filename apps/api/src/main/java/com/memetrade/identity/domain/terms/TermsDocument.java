package com.memetrade.identity.domain.terms;

import java.util.Objects;

/**
 * Terms of use and risk notice, versioned together (entities.md TermsDocument, Q6). Read-only data: there is no table
 * and no route to edit it. The texts are plain text, shown as paragraphs and never as HTML.
 */
public record TermsDocument(String version, String termsText, String riskNoticeText) {

    public TermsDocument {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(termsText, "termsText");
        Objects.requireNonNull(riskNoticeText, "riskNoticeText");
    }
}
