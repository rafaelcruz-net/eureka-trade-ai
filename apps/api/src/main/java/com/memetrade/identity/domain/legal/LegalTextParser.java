package com.memetrade.identity.domain.legal;

import com.memetrade.identity.api.RetentionItem;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Plain-text conventions of the catalog (BR1.4, Q2 of the functional design): a line starting with {@code "## "} opens
 * a section; blank lines separate paragraphs; the lines of one paragraph are joined with a space. The text is never
 * interpreted as markup. A legacy text written before this convention (no heading) becomes one section named after the
 * document; a non-legacy text starts with a heading. Summaries are one topic per non-blank line; the retention table
 * is tab-separated with four columns.
 */
public final class LegalTextParser {

    public static final String HEADING = "## ";
    public static final int MIN_SUMMARY_TOPICS = 5;
    public static final int MAX_SUMMARY_TOPICS = 7;
    private static final String RETENTION_HEADER = "dataitem\tpurpose\tperiod\tendaction";

    private LegalTextParser() {}

    /**
     * Sections of one text.
     *
     * @param where "terms.txt of v2", used in the error message
     * @param defaultTitle title of the implicit section of a legacy text without headings
     */
    public static List<LegalSection> sections(String text, boolean legacy, String defaultTitle, String where) {
        List<Draft> drafts = new ArrayList<>();
        Draft open = null;
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith(HEADING)) {
                open = new Draft(line.substring(HEADING.length()).strip());
                drafts.add(open);
                continue;
            }
            if (open == null) {
                if (line.isBlank()) {
                    continue;
                }
                if (!legacy) {
                    throw invalid(where + " has text before its first '" + HEADING.strip() + "' section");
                }
                open = new Draft(defaultTitle);
                drafts.add(open);
            }
            open.add(line);
        }
        if (drafts.isEmpty()) {
            throw invalid(where + " has no section");
        }
        return toSections(drafts, where);
    }

    /** Topics of a summary file (BR1.3): 5 to 7 non-blank lines; a leading "- " or "* " is dropped. */
    public static List<String> summary(String text, String where) {
        List<String> topics = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            String topic = line.strip();
            if (topic.startsWith("- ") || topic.startsWith("* ")) {
                topic = topic.substring(2).strip();
            }
            if (!topic.isEmpty()) {
                topics.add(topic);
            }
        }
        if (topics.size() < MIN_SUMMARY_TOPICS || topics.size() > MAX_SUMMARY_TOPICS) {
            throw invalid(where + " must have " + MIN_SUMMARY_TOPICS + " to " + MAX_SUMMARY_TOPICS + " topics, found "
                    + topics.size());
        }
        return List.copyOf(topics);
    }

    /**
     * Rows of the retention table: tab-separated {@code dataItem, purpose, period, endAction}; blank lines, lines
     * starting with {@code #} and the optional header row are skipped. At least one row.
     */
    public static List<RetentionItem> retention(String text, String where) {
        List<RetentionItem> rows = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").split("\n");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank() || line.startsWith("#") || line.strip().equalsIgnoreCase(RETENTION_HEADER)) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 4 || anyBlank(fields)) {
                throw invalid(where + " line " + (index + 1) + " must have 4 non-empty tab-separated fields");
            }
            rows.add(new RetentionItem(fields[0].strip(), fields[1].strip(), fields[2].strip(), fields[3].strip()));
        }
        if (rows.isEmpty()) {
            throw invalid(where + " has no row");
        }
        return List.copyOf(rows);
    }

    /** Anchor of a title: lower case, no accents, words joined by hyphens ("Uso do serviço" -> "uso-do-servico"). */
    public static String anchor(String title) {
        String slug = Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.isEmpty() ? "secao" : slug;
    }

    private static List<LegalSection> toSections(List<Draft> drafts, String where) {
        Set<String> used = new HashSet<>();
        List<LegalSection> sections = new ArrayList<>();
        for (Draft draft : drafts) {
            if (draft.title.isEmpty()) {
                throw invalid(where + " has a section without a title");
            }
            List<String> paragraphs = draft.paragraphs();
            if (paragraphs.isEmpty()) {
                throw invalid(where + " has a section without text: " + draft.title);
            }
            sections.add(new LegalSection(unique(anchor(draft.title), used), draft.title, paragraphs));
        }
        return sections;
    }

    private static String unique(String base, Set<String> used) {
        String candidate = base;
        for (int suffix = 2; !used.add(candidate); suffix++) {
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    private static boolean anyBlank(String[] fields) {
        for (String field : fields) {
            if (field.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static InvalidLegalCatalogException invalid(String problem) {
        return new InvalidLegalCatalogException(problem);
    }

    /** A section being read: its title and its raw lines. */
    private static final class Draft {

        private final String title;
        private final List<String> lines = new ArrayList<>();

        private Draft(String title) {
            this.title = title;
        }

        private void add(String line) {
            lines.add(line);
        }

        private List<String> paragraphs() {
            List<String> paragraphs = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String line : lines) {
                if (line.isBlank()) {
                    flush(current, paragraphs);
                } else {
                    current.append(current.isEmpty() ? "" : " ").append(line.strip());
                }
            }
            flush(current, paragraphs);
            return paragraphs;
        }

        private static void flush(StringBuilder current, List<String> paragraphs) {
            if (!current.isEmpty()) {
                paragraphs.add(current.toString());
                current.setLength(0);
            }
        }
    }
}
