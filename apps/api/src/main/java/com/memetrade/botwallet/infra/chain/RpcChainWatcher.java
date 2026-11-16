package com.memetrade.botwallet.infra.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.botwallet.domain.ChainObservation;
import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.ChainUnavailableException;
import com.memetrade.botwallet.infra.chain.SolanaRpcProvider.RpcUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ChainWatcher over two Solana RPC providers (M3, reliability-design.md, infrastructure-specification.md addendum):
 * reads in commitment finalized; balances in one getMultipleAccounts of up to 100 addresses; withdrawal statuses in one
 * getSignatureStatuses per provider, with the finalized height of each provider so FAILED needs both to agree; the
 * other calls use the first provider whose circuit is closed and whose budget allows the call.
 */
public class RpcChainWatcher implements ChainPort, com.memetrade.botwallet.domain.SwapChain {

    /** The error code of the Jupiter v6 program when the output is below the minimum (SlippageToleranceExceeded). */
    static final long JUPITER_SLIPPAGE_ERROR = 6001L;

    private static final Map<String, Object> FINALIZED = Map.of("commitment", "finalized");
    private static final int MAX_ACCOUNTS = 100;

    private final List<SolanaRpcProvider> providers;
    private final RpcBudget budget;

    public RpcChainWatcher(List<SolanaRpcProvider> providers, RpcBudget budget) {
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("At least one RPC provider is required");
        }
        this.providers = List.copyOf(providers);
        this.budget = budget;
    }

    @Override
    public IncomingPage incomingSince(String address, String afterSignature, int limit) {
        return firstAnswering(Purpose.DEPOSITS, provider -> {
            Map<String, Object> options = new LinkedHashMap<>(FINALIZED);
            options.put("limit", limit);
            if (afterSignature != null) {
                options.put("until", afterSignature);
            }
            List<JsonNode> newestFirst = new ArrayList<>();
            provider.call("getSignaturesForAddress", address, options).forEach(newestFirst::add);
            Collections.reverse(newestFirst);
            List<IncomingTransfer> transfers = new ArrayList<>();
            String last = null;
            for (JsonNode entry : newestFirst) {
                last = entry.path("signature").asText();
                if (entry.path("err").isNull() || entry.path("err").isMissingNode()) {
                    incoming(provider, last, address).ifPresent(transfers::add);
                }
            }
            return new IncomingPage(transfers, last);
        });
    }

    private java.util.Optional<IncomingTransfer> incoming(SolanaRpcProvider provider, String signature, String to) {
        JsonNode transaction = provider.call(
                "getTransaction",
                signature,
                Map.of("encoding", "jsonParsed", "commitment", "finalized", "maxSupportedTransactionVersion", 0));
        long lamports = 0;
        String source = null;
        for (JsonNode instruction :
                transaction.path("transaction").path("message").path("instructions")) {
            JsonNode parsed = instruction.path("parsed");
            JsonNode info = parsed.path("info");
            if ("system".equals(instruction.path("program").asText())
                    && "transfer".equals(parsed.path("type").asText())
                    && to.equals(info.path("destination").asText())) {
                lamports = Math.addExact(lamports, info.path("lamports").asLong());
                source = source == null ? info.path("source").asText() : source;
            }
        }
        if (lamports <= 0) {
            return java.util.Optional.empty();
        }
        Instant at = Instant.ofEpochSecond(transaction.path("blockTime").asLong());
        return java.util.Optional.of(new IncomingTransfer(signature, source, lamports, at));
    }

    @Override
    public Map<String, Long> balances(List<String> addresses) {
        Map<String, Long> balances = new LinkedHashMap<>();
        for (int from = 0; from < addresses.size(); from += MAX_ACCOUNTS) {
            List<String> slice = addresses.subList(from, Math.min(addresses.size(), from + MAX_ACCOUNTS));
            JsonNode values =
                    firstAnswering(Purpose.DEPOSITS, provider -> provider.call("getMultipleAccounts", slice, FINALIZED)
                            .path("value"));
            for (int i = 0; i < slice.size(); i++) {
                JsonNode account = values.path(i);
                balances.put(
                        slice.get(i),
                        account.isNull() || account.isMissingNode()
                                ? 0L
                                : account.path("lamports").asLong());
            }
        }
        return balances;
    }

    @Override
    public Map<String, List<ChainObservation>> checkSignatures(List<String> signatures) {
        Map<String, List<ChainObservation>> result = new LinkedHashMap<>();
        signatures.forEach(signature -> result.put(signature, new ArrayList<>()));
        for (SolanaRpcProvider provider : providers) {
            Map<String, ChainObservation> seen = observe(provider, signatures);
            signatures.forEach(signature -> result.get(signature).add(seen.get(signature)));
        }
        if (providers.size() == 1) {
            // A single configured provider never reaches FAILED on its own (two-provider rule).
            signatures.forEach(signature -> result.get(signature).add(ChainObservation.unavailable()));
        }
        return result;
    }

    private Map<String, ChainObservation> observe(SolanaRpcProvider provider, List<String> signatures) {
        Map<String, ChainObservation> seen = new LinkedHashMap<>();
        if (signatures.isEmpty() || !budget.tryAcquire(provider.name(), Purpose.WITHDRAWALS)) {
            signatures.forEach(signature -> seen.put(signature, ChainObservation.unavailable()));
            return seen;
        }
        try {
            long height = provider.call("getBlockHeight", FINALIZED).asLong();
            JsonNode statuses = provider.call(
                            "getSignatureStatuses", signatures, Map.of("searchTransactionHistory", true))
                    .path("value");
            for (int i = 0; i < signatures.size(); i++) {
                JsonNode status = statuses.path(i);
                boolean finalized =
                        "finalized".equals(status.path("confirmationStatus").asText())
                                && status.path("err").isNull();
                seen.put(
                        signatures.get(i),
                        finalized
                                ? ChainObservation.finalized(signatures.get(i), fee(provider, signatures.get(i)))
                                : ChainObservation.notFound(status.isNull() || status.isMissingNode() ? height : -1));
            }
        } catch (RpcUnavailableException | SolanaRpcProvider.RpcRefusedException down) {
            signatures.forEach(signature -> seen.put(signature, ChainObservation.unavailable()));
        }
        return seen;
    }

    private static long fee(SolanaRpcProvider provider, String signature) {
        return provider.call(
                        "getTransaction",
                        signature,
                        Map.of("encoding", "json", "commitment", "finalized", "maxSupportedTransactionVersion", 0))
                .path("meta")
                .path("fee")
                .asLong();
    }

    @Override
    public RecentBlockhash latestBlockhash() {
        JsonNode value = firstAnswering(Purpose.WITHDRAWALS, provider -> provider.call("getLatestBlockhash", FINALIZED)
                .path("value"));
        return new RecentBlockhash(
                value.path("blockhash").asText(),
                value.path("lastValidBlockHeight").asLong());
    }

    @Override
    public String send(String signedTransactionBase64) {
        return firstAnswering(Purpose.WITHDRAWALS, provider -> provider.call(
                        "sendTransaction",
                        signedTransactionBase64,
                        Map.of("encoding", "base64", "preflightCommitment", "finalized"))
                .asText());
    }

    @Override
    public long rentExemptMinimum() {
        return firstAnswering(Purpose.WITHDRAWALS, provider -> provider.call("getMinimumBalanceForRentExemption", 0)
                .asLong());
    }

    // ----- 261113-trading-real-bot U4 (SwapChain) -----

    @Override
    public long blockHeight() {
        return firstAnswering(Purpose.WITHDRAWALS, provider -> provider.call("getBlockHeight", FINALIZED)
                .asLong());
    }

    @Override
    public SignatureStatus status(String txSignature) {
        JsonNode status = firstAnswering(Purpose.WITHDRAWALS, provider -> provider.call(
                        "getSignatureStatuses", List.of(txSignature), Map.of("searchTransactionHistory", true))
                .path("value")
                .path(0));
        if (status.isNull() || status.isMissingNode()) {
            return SignatureStatus.NOT_FOUND;
        }
        boolean finalized = "finalized".equals(status.path("confirmationStatus").asText());
        JsonNode error = status.path("err");
        if (!finalized) {
            return SignatureStatus.PENDING;
        }
        if (error.isNull() || error.isMissingNode()) {
            return SignatureStatus.FINALIZED;
        }
        return isSlippage(error) ? SignatureStatus.FAILED_SLIPPAGE : SignatureStatus.FAILED;
    }

    /** {@code {"InstructionError":[n,{"Custom":6001}]}} of the Jupiter program. */
    static boolean isSlippage(JsonNode error) {
        JsonNode instruction = error.path("InstructionError");
        return instruction.isArray()
                && instruction.size() == 2
                && instruction.get(1).path("Custom").asLong(-1) == JUPITER_SLIPPAGE_ERROR;
    }

    @Override
    public Effect effect(String txSignature, String owner, String mint) {
        JsonNode transaction = firstAnswering(
                Purpose.WITHDRAWALS,
                provider -> provider.call(
                        "getTransaction",
                        txSignature,
                        Map.of(
                                "encoding",
                                "jsonParsed",
                                "commitment",
                                "finalized",
                                "maxSupportedTransactionVersion",
                                0)));
        JsonNode meta = transaction.path("meta");
        JsonNode keys = transaction.path("transaction").path("message").path("accountKeys");
        int index = -1;
        for (int i = 0; i < keys.size(); i++) {
            JsonNode key = keys.get(i);
            String address = key.isTextual() ? key.asText() : key.path("pubkey").asText();
            if (owner.equals(address)) {
                index = i;
                break;
            }
        }
        long lamports = index < 0
                ? 0
                : Math.subtractExact(
                        meta.path("postBalances").path(index).asLong(),
                        meta.path("preBalances").path(index).asLong());
        long tokens = Math.subtractExact(
                tokenBalance(meta.path("postTokenBalances"), owner, mint),
                tokenBalance(meta.path("preTokenBalances"), owner, mint));
        return new Effect(lamports, tokens, meta.path("fee").asLong(), index == 0);
    }

    private static long tokenBalance(JsonNode balances, String owner, String mint) {
        long total = 0;
        for (JsonNode balance : balances) {
            if (owner.equals(balance.path("owner").asText())
                    && mint.equals(balance.path("mint").asText())) {
                total = Math.addExact(
                        total,
                        Long.parseLong(
                                balance.path("uiTokenAmount").path("amount").asText("0")));
            }
        }
        return total;
    }

    // ----- 261113-trading-real-bot U5 (performance-design.md "ChainPort ampliada") -----

    /** The two token programs whose accounts hold the mints of a wallet. */
    static final List<String> TOKEN_PROGRAMS =
            List.of("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");

    @Override
    public Map<String, Long> tokenBalances(String owner) {
        Map<String, Long> balances = new java.util.TreeMap<>();
        for (String program : TOKEN_PROGRAMS) {
            JsonNode accounts = firstAnswering(Purpose.RECONCILIATION, provider -> provider.call(
                            "getTokenAccountsByOwner",
                            owner,
                            Map.of("programId", program),
                            Map.of("encoding", "jsonParsed", "commitment", "finalized"))
                    .path("value"));
            for (JsonNode account : accounts) {
                JsonNode info =
                        account.path("account").path("data").path("parsed").path("info");
                String mint = info.path("mint").asText(null);
                if (mint == null || mint.isBlank()) {
                    continue;
                }
                long amount =
                        Long.parseLong(info.path("tokenAmount").path("amount").asText("0"));
                balances.merge(mint, amount, Math::addExact);
            }
        }
        return balances;
    }

    @Override
    public long finalizedSlot() {
        return firstAnswering(Purpose.RECONCILIATION, provider -> provider.call("getSlot", FINALIZED)
                .asLong());
    }

    @Override
    public SignaturePage signaturesSince(Purpose purpose, String address, String until, String before, int limit) {
        int bounded = Math.max(1, Math.min(1_000, limit));
        return firstAnswering(purpose, provider -> {
            Map<String, Object> options = new LinkedHashMap<>(FINALIZED);
            options.put("limit", bounded);
            if (until != null) {
                options.put("until", until);
            }
            if (before != null) {
                options.put("before", before);
            }
            List<SignatureInfo> page = new ArrayList<>();
            for (JsonNode entry : provider.call("getSignaturesForAddress", address, options)) {
                JsonNode error = entry.path("err");
                page.add(new SignatureInfo(
                        entry.path("signature").asText(), !(error.isNull() || error.isMissingNode())));
            }
            return new SignaturePage(page);
        });
    }

    @Override
    public List<String> transactionSigners(String signature) {
        JsonNode transaction = firstAnswering(
                Purpose.RESTORE_CHECK,
                provider -> provider.call(
                        "getTransaction",
                        signature,
                        Map.of("encoding", "json", "commitment", "finalized", "maxSupportedTransactionVersion", 0)));
        JsonNode message =
                transaction == null ? null : transaction.path("transaction").path("message");
        if (message == null
                || !message.path("header").path("numRequiredSignatures").isInt()) {
            // A listed signature with no finalized transaction yet must keep the restore check pending, never fail open
            throw new ChainUnavailableException("Transaction not available yet");
        }
        int required = message.path("header").path("numRequiredSignatures").asInt();
        List<String> signers = new ArrayList<>();
        JsonNode keys = message.path("accountKeys");
        for (int i = 0; i < required && i < keys.size(); i++) {
            signers.add(keys.get(i).asText());
        }
        return signers;
    }

    private <T> T firstAnswering(Purpose purpose, Function<SolanaRpcProvider, T> call) {
        for (SolanaRpcProvider provider : providers) {
            if (!budget.tryAcquire(provider.name(), purpose)) {
                continue;
            }
            try {
                return call.apply(provider);
            } catch (RpcUnavailableException down) {
                // Technical failure of this provider (already counted by its circuit): the next one is tried.
            }
        }
        throw new ChainUnavailableException("No RPC provider answered");
    }
}
