package com.memetrade.identity.infra;

import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Reads the versioned terms and risk notice ONCE at start-up and keeps them in memory (NFR3.2, NFR10.3, NFR13.1).
 * Each version is a folder under {@code memetrade.terms.location} with {@code version.properties}
 * ({@code version}, {@code current}), {@code terms.txt} and {@code risk-notice.txt}, in UTF-8. Fails fast, with a clear
 * message, when there is no current version, more than one, a repeated version, an invalid name or an empty text:
 * the api does not start without terms.
 */
public class TermsCatalog implements CurrentTerms {

    private static final Logger log = LoggerFactory.getLogger(TermsCatalog.class);

    /** Stable identifier stored in terms_acceptance.terms_version (varchar(32)). */
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");

    private final Map<String, TermsDocument> versions;
    private final TermsDocument current;

    public TermsCatalog(ResourcePatternResolver resources, TermsProperties properties) {
        List<Loaded> loaded = load(resources, properties.location());
        Map<String, TermsDocument> byVersion = new LinkedHashMap<>();
        List<TermsDocument> currents = new ArrayList<>();
        for (Loaded item : loaded) {
            if (byVersion.putIfAbsent(item.document().version(), item.document()) != null) {
                throw invalid("the version " + item.document().version() + " appears more than once");
            }
            if (item.current()) {
                currents.add(item.document());
            }
        }
        if (currents.size() != 1) {
            throw invalid("exactly one version must have current=true, found " + currents.size());
        }
        this.versions = Map.copyOf(byVersion);
        this.current = currents.getFirst();
        log.info("Terms loaded: current version {}, {} version(s) known", current.version(), versions.size());
    }

    @Override
    public TermsDocument current() {
        return current;
    }

    @Override
    public Optional<TermsDocument> find(String version) {
        return Optional.ofNullable(version == null ? null : versions.get(version));
    }

    private static List<Loaded> load(ResourcePatternResolver resources, String location) {
        Resource[] descriptors;
        try {
            descriptors = resources.getResources(location + "*/version.properties");
        } catch (IOException failure) {
            throw new IllegalStateException("Terms could not be listed under " + location, failure);
        }
        if (descriptors.length == 0) {
            throw invalid("no version folder with version.properties under " + location);
        }
        List<Loaded> loaded = new ArrayList<>();
        for (Resource descriptor : descriptors) {
            loaded.add(loadVersion(descriptor));
        }
        return loaded;
    }

    private static Loaded loadVersion(Resource descriptor) {
        Properties properties = new Properties();
        try (InputStream in = descriptor.getInputStream()) {
            properties.load(in);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Terms descriptor could not be read: " + descriptor.getDescription(), failure);
        }
        String version = properties.getProperty("version", "").trim();
        if (!VERSION.matcher(version).matches()) {
            throw invalid("version.properties must name a version of 1 to 32 letters, digits, '.', '_' or '-'");
        }
        String termsText = text(descriptor, "terms.txt", version);
        String riskNoticeText = text(descriptor, "risk-notice.txt", version);
        boolean current =
                Boolean.parseBoolean(properties.getProperty("current", "false").trim());
        return new Loaded(new TermsDocument(version, termsText, riskNoticeText), current);
    }

    private static String text(Resource descriptor, String name, String version) {
        try {
            Resource file = descriptor.createRelative(name);
            if (!file.exists()) {
                throw invalid("version " + version + " has no " + name);
            }
            byte[] bytes;
            try (InputStream in = file.getInputStream()) {
                bytes = in.readAllBytes();
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
            return text;
        } catch (CharacterCodingException notUtf8) {
            throw invalid("version " + version + " has a " + name + " that is not UTF-8");
        } catch (IOException failure) {
            throw new IllegalStateException("Terms file could not be read: " + name + " of " + version, failure);
        }
    }

    private static IllegalStateException invalid(String problem) {
        return new IllegalStateException(
                "The api cannot start without valid terms: " + problem + " (memetrade.terms.location, BR3.1)");
    }

    private record Loaded(TermsDocument document, boolean current) {}
}
