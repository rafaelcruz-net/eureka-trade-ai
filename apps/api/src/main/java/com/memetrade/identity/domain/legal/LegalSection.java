package com.memetrade.identity.domain.legal;

import java.util.List;
import java.util.Objects;

/** One section of a legal text (entities.md LegalSection): anchor id, title and plain-text paragraphs, never HTML. */
public record LegalSection(String id, String title, List<String> paragraphs) {

    public LegalSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        paragraphs = List.copyOf(paragraphs);
    }
}
