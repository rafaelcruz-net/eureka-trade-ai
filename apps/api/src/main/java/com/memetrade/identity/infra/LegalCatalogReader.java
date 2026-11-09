package com.memetrade.identity.infra;

import com.memetrade.identity.api.RetentionItem;
import com.memetrade.identity.domain.legal.InvalidLegalCatalogException;
import com.memetrade.identity.domain.legal.LegalSection;
import com.memetrade.identity.domain.legal.LegalText;
import com.memetrade.identity.domain.legal.LegalTextParser;
import com.memetrade.identity.domain.legal.LegalVersion;
import com.memetrade.identity.domain.terms.TermsDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Reads the folders of the legal catalog (functional-spec.md "Decisões de implementação registradas"): each version is
 * a folder with {@code version.properties} ({@code version}, {@code current}, {@code legacy}, {@code effectiveAt},
 * {@code legalReviewedAt}, {@code legalReviewedBy}) and the UTF-8 files {@code terms.txt}, {@code privacy.txt},
 * {@code risk-notice.txt}, {@code terms-summary.txt}, {@code privacy-summary.txt} and {@code retention.tsv}. A
 * non-legacy version needs every file (BR1.3); a legacy one needs only the terms and the risk notice. Each file has at
 * most 200 KB and each version at most 100 sections (performance-design.md Q3). Problems are reported as
 * {@link InvalidLegalCatalogException}, naming version and file, never the text.
 */
final class LegalCatalogReader {

    /** Stable identifier stored in terms_acceptance.terms_version (varchar(32)). */
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");

    static final int MAX_FILE_BYTES = 200 * 1024;
    static final int MAX_SECTIONS = 100;
    static final int MAX_REVIEWER_LENGTH = 120;

    private LegalCatalogReader() {}

    /** One version: the structured documents of L1 and the plain texts used by the invitation and the Account. */
    record LoadedVersion(LegalVersion legal, TermsDocument document) {}

    static List<LoadedVersion> read(ResourcePatternResolver resources, String location) {
        Resource[] descriptors;
        try {
            descriptors = resources.getResources(location + "*/version.properties");
        } catch (IOException failure) {
            throw new IllegalStateException("Terms could not be listed under " + location, failure);
        }
        if (descriptors.length == 0) {
            throw invalid("no version folder with version.properties under " + location);
        }
        List<LoadedVersion> loaded = new ArrayList<>();
        for (Resource descriptor : descriptors) {
            loaded.add(readVersion(descriptor));
        }
        return loaded;
    }

    private static LoadedVersion readVersion(Resource descriptor) {
        Properties properties = properties(descriptor);
        String version = properties.getProperty("version", "").trim();
        if (!VERSION.matcher(version).matches()) {
            throw invalid("version.properties must name a version of 1 to 32 letters, digits, '.', '_' or '-'");
        }
        boolean legacy = flag(properties, "legacy");
        String termsRaw = text(descriptor, "terms.txt", version).orElseThrow(() -> missing(version, "terms.txt"));
        String riskRaw =
                text(descriptor, "risk-notice.txt", version).orElseThrow(() -> missing(version, "risk-notice.txt"));
        Instant effectiveAt = effectiveAt(properties, version);
        Review review = review(properties, version);
        LegalText terms = legalText(
                termsRaw,
                optionalSummary(descriptor, "terms-summary.txt", version, legacy),
                legacy,
                "Termos de uso",
                "terms.txt of " + version);
        LegalText riskNotice = new LegalText(
                List.of(),
                LegalTextParser.sections(riskRaw, legacy, "Aviso de risco", "risk-notice.txt of " + version));
        LegalText privacy = privacy(descriptor, version, legacy);
        List<RetentionItem> retention = text(descriptor, "retention.tsv", version)
                .map(raw -> LegalTextParser.retention(raw, "retention.tsv of " + version))
                .orElseGet(() -> requiredWhenNotLegacy(version, legacy, "retention.tsv", List.of()));
        LegalVersion legal = new LegalVersion(
                version,
                effectiveAt,
                flag(properties, "current"),
                legacy,
                review.at(),
                review.by(),
                terms,
                privacy,
                riskNotice,
                retention);
        if (legal.sectionCount() > MAX_SECTIONS) {
            throw invalid(version + " has more than " + MAX_SECTIONS + " sections");
        }
        return new LoadedVersion(legal, new TermsDocument(version, plain(termsRaw), plain(riskRaw)));
    }

    private static LegalText privacy(Resource descriptor, String version, boolean legacy) {
        Optional<String> raw = text(descriptor, "privacy.txt", version);
        if (raw.isEmpty()) {
            return requiredWhenNotLegacy(version, legacy, "privacy.txt", null);
        }
        return legalText(
                raw.get(),
                optionalSummary(descriptor, "privacy-summary.txt", version, legacy),
                legacy,
                "Política de privacidade",
                "privacy.txt of " + version);
    }

    private static LegalText legalText(
            String raw, List<String> summary, boolean legacy, String defaultTitle, String where) {
        List<LegalSection> sections = LegalTextParser.sections(raw, legacy, defaultTitle, where);
        return new LegalText(summary, sections);
    }

    private static List<String> optionalSummary(Resource descriptor, String name, String version, boolean legacy) {
        return text(descriptor, name, version)
                .map(raw -> LegalTextParser.summary(raw, name + " of " + version))
                .orElseGet(() -> requiredWhenNotLegacy(version, legacy, name, List.of()));
    }

    private static <T> T requiredWhenNotLegacy(String version, boolean legacy, String name, T legacyValue) {
        if (!legacy) {
            throw missing(version, name);
        }
        return legacyValue;
    }

    private static Instant effectiveAt(Properties properties, String version) {
        String value = properties.getProperty("effectiveAt", "").trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            throw invalid("version " + version + " has no valid effectiveAt (ISO-8601 UTC instant, for example "
                    + "2026-11-01T00:00:00Z)");
        }
    }

    private static Review review(Properties properties, String version) {
        String at = properties.getProperty("legalReviewedAt", "").trim();
        String by = properties.getProperty("legalReviewedBy", "").trim();
        if (at.isEmpty() && by.isEmpty()) {
            return new Review(null, null);
        }
        if (at.isEmpty() || by.isEmpty()) {
            throw invalid(version + " must have both legalReviewedAt and legalReviewedBy, or neither");
        }
        if (by.length() > MAX_REVIEWER_LENGTH) {
            throw invalid(version + " has a legalReviewedBy longer than " + MAX_REVIEWER_LENGTH + " characters");
        }
        try {
            return new Review(LocalDate.parse(at), by);
        } catch (DateTimeParseException notADate) {
            throw invalid(version + " has a legalReviewedAt that is not a date (yyyy-mm-dd)");
        }
    }

    private static boolean flag(Properties properties, String name) {
        return Boolean.parseBoolean(properties.getProperty(name, "false").trim());
    }

    private static Properties properties(Resource descriptor) {
        Properties properties = new Properties();
        try (InputStream in = descriptor.getInputStream()) {
            properties.load(in);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Terms descriptor could not be read: " + descriptor.getDescription(), failure);
        }
        return properties;
    }

    /** The UTF-8 text of a file of the version, without CR and surrounding blanks; empty when the file is absent. */
    private static Optional<String> text(Resource descriptor, String name, String version) {
        try {
            Resource file = descriptor.createRelative(name);
            if (!file.exists()) {
                return Optional.empty();
            }
            byte[] bytes;
            try (InputStream in = file.getInputStream()) {
                bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw invalid(name + " of " + version + " is larger than 200 KB");
            }
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .replace("\r\n", "\n")
                    .strip();
            if (text.isEmpty()) {
                throw invalid("version " + version + " has an empty " + name);
            }
            return Optional.of(text);
        } catch (CharacterCodingException notUtf8) {
            throw invalid("version " + version + " has a " + name + " that is not UTF-8");
        } catch (IOException failure) {
            throw new IllegalStateException("Terms file could not be read: " + name + " of " + version, failure);
        }
    }

    /** The text for the invitation and the Account screens: the section headings without their "## " marker. */
    private static String plain(String raw) {
        return raw.lines()
                .map(line -> line.startsWith(LegalTextParser.HEADING)
                        ? line.substring(LegalTextParser.HEADING.length()).strip()
                        : line)
                .collect(Collectors.joining("\n"));
    }

    private static InvalidLegalCatalogException missing(String version, String name) {
        return invalid("version " + version + " has no " + name);
    }

    private static InvalidLegalCatalogException invalid(String problem) {
        return new InvalidLegalCatalogException(problem);
    }

    private record Review(LocalDate at, String by) {}
}
