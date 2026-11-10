package com.memetrade.identity.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.RetentionTableSource;
import com.memetrade.identity.domain.legal.ActiveLegalDocuments;
import com.memetrade.identity.domain.legal.LegalVersion;
import com.memetrade.identity.infra.TermsCatalog;
import com.memetrade.identity.web.LegalDtos.LegalDocumentsResponse;
import com.memetrade.identity.web.LegalDtos.LegalVersionRef;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The answers of L1, built ONCE at start-up from the activation and the J5 port (performance-design.md "Caminho da
 * requisição", NFR3.5): one ready body per version of the history and the history list, each with its strong
 * {@code ETag}, the SHA-256 of its JSON body (infrastructure-specification.md "ETag por hash do conteúdo"). Nothing is
 * read from the database or another module at request time (logical-components.md "Isolamento").
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LegalDocumentsView {

    /** A ready body and its entity tag. */
    public record Prepared<T>(T body, String etag) {}

    private final String currentVersion;
    private final Map<String, Prepared<LegalDocumentsResponse>> byVersion;
    private final Prepared<List<LegalVersionRef>> history;

    public LegalDocumentsView(TermsCatalog catalog, RetentionTableSource retention, ObjectMapper objectMapper) {
        ActiveLegalDocuments active = catalog.active();
        this.currentVersion = active.current().version();
        Map<String, Prepared<LegalDocumentsResponse>> prepared = new LinkedHashMap<>();
        for (LegalVersion version : active.history()) {
            // BR1.8: the current version shows the table of the J5 port (the Privacy catalog, u2-retention BR2.9); an
            // older one shows the table it declared, and a legacy current version keeps none (BR1.15).
            boolean fromPort = version.version().equals(currentVersion) && !version.legacy();
            LegalDocumentsResponse body = LegalDocumentsResponse.of(
                    version,
                    active.publishable(),
                    active.deployment(),
                    fromPort ? retention.items() : version.retention());
            prepared.put(version.version(), new Prepared<>(body, etag(objectMapper, body)));
        }
        this.byVersion = Map.copyOf(prepared);
        List<LegalVersionRef> refs =
                active.history().stream().map(LegalVersionRef::of).toList();
        this.history = new Prepared<>(refs, etag(objectMapper, refs));
    }

    public Prepared<LegalDocumentsResponse> current() {
        return byVersion.get(currentVersion);
    }

    /** A version of the history; any other version is absent (BR1.10). */
    public Optional<Prepared<LegalDocumentsResponse>> version(String version) {
        return Optional.ofNullable(version == null ? null : byVersion.get(version));
    }

    public Prepared<List<LegalVersionRef>> history() {
        return history;
    }

    static String etag(ObjectMapper objectMapper, Object body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(body));
            return "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The legal documents could not be serialized at start-up", failure);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", missing);
        }
    }
}
