package com.memetrade.botwallet.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.botwallet.api.ExitsAvailability;
import com.memetrade.botwallet.api.RealReleaseInfo;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.application.BotWalletService;
import com.memetrade.botwallet.application.BotWalletViews;
import com.memetrade.botwallet.domain.BotWalletError;
import com.memetrade.botwallet.domain.BotWalletException;
import com.memetrade.botwallet.domain.ConsentKind;
import com.memetrade.botwallet.domain.OperationMode;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes R2 of the bot wallet (contract-summary.md) under {@code /api/v1/real}. The tenant and the person come only
 * from the authentication; the withdrawal has NO destination field: it always goes to the registered main wallet. The
 * challenge, the activation and the partial withdrawal are limited per person (NFR1.9, {@link BotWalletRateLimits});
 * the total withdrawal and the readings never are.
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
@RequestMapping("/api/v1/real")
@Tag(name = "REAL — carteira do bot")
public class BotWalletController {

    private static final Pattern LAMPORTS = Pattern.compile("^[0-9]{1,20}$");
    private static final Pattern SIGNATURE = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{64,90}$");

    private final BotWalletService service;
    private final RealStateQuery state;
    private final AuthContext auth;
    private final ObjectMapper json;

    /** 261113-trading-real-bot U7 (BR8.1): whether the conditional exits are enabled in this environment. */
    private final ExitsAvailability exits;

    /** NFR1.9: the limits per person of the challenge, the activation and the partial withdrawal. */
    private final BotWalletRateLimits limits;

    public BotWalletController(
            BotWalletService service,
            RealStateQuery state,
            AuthContext auth,
            ObjectMapper json,
            ExitsAvailability exits,
            BotWalletRateLimits limits) {
        this.exits = exits;
        this.limits = limits;
        this.service = service;
        this.state = state;
        this.auth = auth;
        this.json = json;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealChallengeRequest")
    public record ChallengeRequest(ConsentKind kind, OperationMode mode, String capLamports) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealActivationRequest")
    public record ActivationRequest(UUID challengeId, String signature) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealPreferencesRequest")
    public record PreferencesRequest(
            String capLamports, Integer maxSlippageBps, Boolean paused, UUID challengeId, String signature) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealWithdrawalAmount")
    public record Amount(String lamports, Boolean all) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealWithdrawalRequest")
    public record WithdrawalRequest(Amount amount) {}

    @Operation(
            operationId = "issueRealConsentChallenge",
            summary = "Emite o desafio de consentimento",
            security = @SecurityRequirement(name = "accessToken"))
    @PostMapping("/consents/challenge")
    public ResponseEntity<BotWalletViews.ChallengeView> challenge(@RequestBody ChallengeRequest body) {
        if (body.kind() == null) {
            throw ApiException.validation("kind", "Informe ACTIVATE, RAISE_MODE ou RAISE_CAP.");
        }
        var person = auth.current();
        limit(BotWalletRateLimits.Route.CHALLENGE, person.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(service.issueChallenge(
                        person, body.kind(), body.mode(), lamports("capLamports", body.capLamports())));
    }

    @Operation(
            operationId = "getBotWallet",
            summary = "Carteira do bot do próprio usuário",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/bot-wallet")
    public ResponseEntity<BotWalletViews.BotWalletView> view() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(auth.current()));
    }

    /**
     * 261113-trading-real-bot U6 (rules.md "Mudança de contrato" item 1, U6-NFR1.6): the release of the person of the
     * token, read through the M1; {@code 403 REAL_DISABLED} with the REAL switched off. It needs the acceptance of the
     * current terms (not one of the exceptions of the reacceptance).
     */
    @Operation(
            operationId = "getRealRelease",
            summary = "Liberação do REAL da própria pessoa na rede do ambiente",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/release")
    public ResponseEntity<BotWalletViews.ReleaseView> release() {
        var person = auth.current();
        RealReleaseInfo info = state.readRelease(person.tenantId(), person.userId());
        if (!info.realEnabled()) {
            throw new BotWalletException(BotWalletError.REAL_DISABLED);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new BotWalletViews.ReleaseView(
                        info.network(),
                        new BotWalletViews.ReleaseStatus(info.state(), info.effectiveAt(), info.blockedReason()),
                        exits.exitsEnabled()));
    }

    @Operation(
            operationId = "activateBotWallet",
            summary = "Ativa a carteira do bot",
            security = @SecurityRequirement(name = "accessToken"))
    @PostMapping("/bot-wallet")
    public ResponseEntity<BotWalletViews.BotWalletView> activate(@RequestBody ActivationRequest body) {
        if (body.challengeId() == null) {
            throw ApiException.validation("challengeId", "Obrigatório.");
        }
        String signature = signature(body.signature());
        var person = auth.current();
        limit(BotWalletRateLimits.Route.ACTIVATION, person.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.activate(person, body.challengeId(), signature));
    }

    @Operation(
            operationId = "changeRealPreferences",
            summary = "Teto, derrapagem e pausa",
            security = @SecurityRequirement(name = "accessToken"))
    @PatchMapping("/bot-wallet/preferences")
    public BotWalletViews.BotWalletView preferences(@RequestBody PreferencesRequest body) {
        return service.changePreferences(
                auth.current(),
                new BotWalletService.PreferencesChange(
                        lamports("capLamports", body.capLamports()),
                        body.maxSlippageBps(),
                        body.paused(),
                        body.challengeId(),
                        body.signature() == null ? null : signature(body.signature())));
    }

    @Operation(
            operationId = "resumeReal",
            summary = "Retoma o REAL pausado pelo próprio usuário",
            security = @SecurityRequirement(name = "accessToken"))
    @PostMapping("/bot-wallet/resume")
    public BotWalletViews.BotWalletView resume() {
        return service.resume(auth.current());
    }

    @Operation(
            operationId = "listRealDeposits",
            summary = "Depósitos detectados",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/bot-wallet/deposits")
    public BotWalletViews.Page<BotWalletViews.DepositView> deposits() {
        return service.deposits(auth.current());
    }

    @Operation(
            operationId = "requestRealWithdrawal",
            summary = "Saque só para a carteira principal",
            security = @SecurityRequirement(name = "accessToken"))
    @PostMapping("/bot-wallet/withdrawals")
    public ResponseEntity<BotWalletViews.WithdrawalView> withdraw(
            @RequestBody WithdrawalRequest body, HttpServletRequest request) {
        if (body.amount() == null) {
            throw ApiException.validation("amount", "Informe lamports ou all.");
        }
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        boolean all = Boolean.TRUE.equals(body.amount().all());
        if (Boolean.FALSE.equals(body.amount().all())) {
            throw ApiException.validation("amount.all", "Use all: true ou lamports.");
        }
        Long lamports = lamports("amount.lamports", body.amount().lamports());
        var person = auth.current();
        // NFR1.9: only the partial withdrawal is limited; the total one is never barred by a limit of use. The limit is
        // consumed only by a NEW request, after the idempotency check (Step 31, review R-11): a retry with the same
        // Idempotency-Key replays the stored answer, and a 429 releases the key, storing nothing under it.
        Runnable admitNew = all
                ? BotWalletService.NO_LIMIT
                : () -> limit(BotWalletRateLimits.Route.PARTIAL_WITHDRAWAL, person.userId());
        IdempotentResponse answer = service.requestWithdrawal(person, key, lamports, all, admitNew);
        try {
            return ResponseEntity.status(answer.status())
                    .cacheControl(CacheControl.noStore())
                    .body(json.readValue(answer.body(), BotWalletViews.WithdrawalView.class));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("A stored withdrawal answer could not be read", failure);
        }
    }

    @Operation(
            operationId = "listRealWithdrawals",
            summary = "Saques",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/bot-wallet/withdrawals")
    public BotWalletViews.Page<BotWalletViews.WithdrawalView> withdrawals() {
        return service.withdrawals(auth.current());
    }

    @Operation(
            operationId = "getRealWithdrawal",
            summary = "Um saque",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/bot-wallet/withdrawals/{withdrawalId}")
    public BotWalletViews.WithdrawalView withdrawal(@PathVariable("withdrawalId") String withdrawalId) {
        UUID id;
        try {
            id = UUID.fromString(withdrawalId);
        } catch (IllegalArgumentException notUuid) {
            throw ApiException.validation("withdrawalId", "Deve ser um UUID.");
        }
        return service.withdrawal(auth.current(), id);
    }

    /** {@code 429 RATE_LIMITED} with {@code Retry-After} (RFC 7807) above the limit of the route for the person. */
    private void limit(BotWalletRateLimits.Route route, UUID userId) {
        Optional<Duration> wait = limits.acquire(route, userId);
        if (wait.isPresent()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMITED,
                    "At most " + route.limit() + " requests in " + route.window() + " on this route.",
                    List.of(),
                    null,
                    Map.of(HttpHeaders.RETRY_AFTER, Long.toString(wait.get().toSeconds())));
        }
    }

    private static Long lamports(String field, String value) {
        if (value == null) {
            return null;
        }
        if (!LAMPORTS.matcher(value).matches()) {
            throw ApiException.validation(field, "Lamports em texto inteiro.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw ApiException.validation(field, "Valor acima do limite.");
        }
    }

    private static String signature(String value) {
        if (value == null || !SIGNATURE.matcher(value).matches()) {
            throw ApiException.validation("signature", "Assinatura Base58 inválida.");
        }
        return value;
    }
}
