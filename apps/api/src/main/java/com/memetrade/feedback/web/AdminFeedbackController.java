package com.memetrade.feedback.web;

import com.memetrade.feedback.domain.FeedbackEntry;
import com.memetrade.feedback.domain.FeedbackStore;
import com.memetrade.feedback.infra.FeedbackProperties;
import com.memetrade.feedback.web.FeedbackDtos.FeedbackItem;
import com.memetrade.feedback.web.FeedbackDtos.FeedbackPage;
import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.IdentityAdministration;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.KeysetCursor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C8: the administrator reads the comments, newest first, by cursor (F8; BR6.5, BR6.6; AC6.1.4). Each item carries the
 * tenant and its name, the screen, the instant and the text as plain text; never the person nor the wallet. There is
 * no guest route that reads feedback.
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class AdminFeedbackController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final String CURSOR_SCOPE = "admin-feedback";

    private final AdminAccess admins;
    private final FeedbackStore store;
    private final IdentityAdministration identity;
    private final int pageMax;

    public AdminFeedbackController(
            AdminAccess admins, FeedbackStore store, IdentityAdministration identity, FeedbackProperties properties) {
        this.admins = admins;
        this.store = store;
        this.identity = identity;
        this.pageMax = properties.pageMax();
    }

    @Operation(
            operationId = "listFeedback",
            summary = "Comentários do mais novo para o mais antigo, como texto puro (AC6.1.4); sem pessoa nem carteira",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = "cursor",
            in = ParameterIn.QUERY,
            description = "Cursor opaco devolvido em nextCursor",
            schema = @Schema(type = "string"))
    @Parameter(
            name = "limit",
            in = ParameterIn.QUERY,
            description = "Itens por página",
            schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
    @ApiResponse(responseCode = "200", description = "Uma página")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED em limit ou cursor",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN_ROLE para token de convidado, auditado como REQUEST_FORBIDDEN",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/api/v1/admin/feedback")
    public ResponseEntity<FeedbackPage> list(
            @Parameter(hidden = true) @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(hidden = true) @RequestParam(name = "limit", required = false) String limit,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        int size = size(limit);
        Optional<FeedbackStore.PageKey> after = Optional.ofNullable(cursor)
                .map(text -> KeysetCursor.decode(text, CURSOR_SCOPE))
                .map(position -> new FeedbackStore.PageKey(position.at(), position.id()));
        List<FeedbackEntry> rows = store.page(after, size + 1);
        List<FeedbackEntry> items = rows.subList(0, Math.min(size, rows.size()));
        Map<UUID, String> names =
                identity.tenantNames(items.stream().map(FeedbackEntry::tenantId).toList(), admin);
        String next = rows.size() > size
                ? new KeysetCursor(items.getLast().createdAt(), items.getLast().feedbackId()).encode(CURSOR_SCOPE)
                : null;
        List<FeedbackItem> view = items.stream()
                .map(entry -> new FeedbackItem(
                        entry.feedbackId(),
                        entry.tenantId(),
                        names.getOrDefault(entry.tenantId(), ""),
                        entry.screen().name(),
                        entry.text(),
                        entry.createdAt()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new FeedbackPage(view, next));
    }

    private int size(String raw) {
        if (raw == null) {
            return DEFAULT_PAGE_SIZE;
        }
        int value = raw.trim().matches("\\d{1,3}") ? Integer.parseInt(raw.trim()) : -1;
        if (value < 1 || value > pageMax) {
            throw ApiException.validation("limit", "Must be an integer from 1 to " + pageMax + ".");
        }
        return value;
    }
}
