package com.memetrade.identity.domain.legal;

import java.util.List;

/**
 * One document of a version (entities.md LegalText): the summary topics (empty when the version declares none, which
 * only a legacy version may do, BR1.3) and the sections (at least one, BR1.4).
 */
public record LegalText(List<String> summary, List<LegalSection> sections) {

    public LegalText {
        summary = List.copyOf(summary);
        sections = List.copyOf(sections);
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("a legal text has at least one section");
        }
    }
}
