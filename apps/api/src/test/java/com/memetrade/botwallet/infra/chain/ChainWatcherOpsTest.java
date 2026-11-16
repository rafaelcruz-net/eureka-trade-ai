package com.memetrade.botwallet.infra.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.ChainReading;
import com.memetrade.botwallet.domain.ChainUnavailableException;
import com.memetrade.botwallet.infra.ReconciliationReader;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 6 of the U5, written after the code (performance-design.md "ChainPort ampliada", U5-NFR3.1, U5-NFR4.3): the new
 * reads of the ChainWatcher against recorded JSON-RPC answers on loopback (never a real provider): the token accounts of
 * both token programs, the finalized slot, a page of the history and the signers of a transaction; the quota of the
 * reconciliation and of the restore check; the reader of the reconciliation, which turns any failure into "unavailable".
 */
@Tag("u5-real-operations")
class ChainWatcherOpsTest {

    private static final String OWNER = "BotWa11et1111111111111111111111111111111111";
    private static final String MINT = "Mint1111111111111111111111111111111111111111";
    private static final String MINT_2022 = "Mint2222222222222222222222222222222222222222";

    private RpcStub first;
    private RpcStub second;

    @BeforeEach
    void start() throws Exception {
        first = new RpcStub();
        second = new RpcStub();
    }

    @AfterEach
    void stop() {
        first.close();
        second.close();
    }

    private RpcChainWatcher watcher(int maxRps) {
        HttpClient http = HttpClient.newHttpClient();
        return new RpcChainWatcher(
                List.of(
                        new SolanaRpcProvider("a", first.uri(), http, Duration.ofSeconds(3)),
                        new SolanaRpcProvider("b", second.uri(), http, Duration.ofSeconds(3))),
                new RpcBudget(Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC), maxRps));
    }

    private static String tokenAccounts(String... mintAndAmount) {
        StringBuilder value = new StringBuilder("{\"context\":{\"slot\":1},\"value\":[");
        for (int i = 0; i < mintAndAmount.length; i += 2) {
            if (i > 0) {
                value.append(',');
            }
            value.append("{\"pubkey\":\"Acc")
                    .append(i)
                    .append("\",\"account\":{\"data\":{\"parsed\":{\"info\":{\"mint\":\"")
                    .append(mintAndAmount[i])
                    .append("\",\"owner\":\"")
                    .append(OWNER)
                    .append("\",\"tokenAmount\":{\"amount\":\"")
                    .append(mintAndAmount[i + 1])
                    .append("\",\"decimals\":6}}}}}}");
        }
        return value.append("]}").toString();
    }

    @Test
    void theTokensOfBothProgramsAreSummedByMint() {
        first.answerWhen(
                "getTokenAccountsByOwner",
                RpcChainWatcher.TOKEN_PROGRAMS.get(0),
                tokenAccounts(MINT, "700", MINT, "50"));
        first.answerWhen(
                "getTokenAccountsByOwner", RpcChainWatcher.TOKEN_PROGRAMS.get(1), tokenAccounts(MINT_2022, "9"));

        Map<String, Long> tokens = watcher(100).tokenBalances(OWNER);

        assertThat(tokens).containsExactlyInAnyOrderEntriesOf(Map.of(MINT, 750L, MINT_2022, 9L));
        assertThat(first.params).anyMatch(p -> p.contains("\"finalized\"") && p.contains("jsonParsed"));
    }

    @Test
    void anEmptyTokenAccountCountsZeroAndNoAccountGivesNoMint() {
        first.answerWhen("getTokenAccountsByOwner", RpcChainWatcher.TOKEN_PROGRAMS.get(0), tokenAccounts(MINT, "0"));
        first.answerWhen("getTokenAccountsByOwner", RpcChainWatcher.TOKEN_PROGRAMS.get(1), tokenAccounts());

        assertThat(watcher(100).tokenBalances(OWNER)).containsExactly(Map.entry(MINT, 0L));
    }

    @Test
    void theSlotIsTheFinalizedOne() {
        first.answer("getSlot", "469939807");

        assertThat(watcher(100).finalizedSlot()).isEqualTo(469_939_807L);
        assertThat(first.params).anyMatch(p -> p.startsWith("getSlot") && p.contains("finalized"));
    }

    @Test
    void aPageOfTheHistoryPassesItsBoundsAndMarksTheFailedTransactions() {
        first.answer(
                "getSignaturesForAddress",
                "[{\"signature\":\"SigNew\",\"err\":null},{\"signature\":\"SigFailed\",\"err\":{\"InstructionError\":"
                        + "[0,\"Custom\"]}}]");

        ChainPort.SignaturePage page =
                watcher(100).signaturesSince(ChainPort.Purpose.RESTORE_CHECK, OWNER, "SigUntil", "SigBefore", 5000);

        assertThat(page.signatures())
                .containsExactly(
                        new ChainPort.SignatureInfo("SigNew", false), new ChainPort.SignatureInfo("SigFailed", true));
        assertThat(first.params.getLast())
                .contains("\"until\":\"SigUntil\"")
                .contains("\"before\":\"SigBefore\"")
                .contains("\"limit\":1000");
    }

    @Test
    void theSignersAreTheRequiredSignaturesOfTheMessage() {
        first.answer(
                "getTransaction",
                "{\"transaction\":{\"message\":{\"header\":{\"numRequiredSignatures\":2},\"accountKeys\":[\"Main\","
                        + "\"Bot\",\"Program\"]}},\"meta\":{\"fee\":5000}}");

        assertThat(watcher(100).transactionSigners("SigX")).containsExactly("Main", "Bot");
    }

    @Test
    void aSignatureWithNoTransactionYetIsUnavailableNotUnsigned() {
        first.answer("getTransaction", "null");

        assertThatThrownBy(() -> watcher(100).transactionSigners("SigX")).isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    void aProviderDownGoesToTheOtherAndBothDownIsUnavailable() {
        first.goDown();
        second.answer("getSlot", "7");

        assertThat(watcher(100).finalizedSlot()).isEqualTo(7L);

        second.goDown();
        assertThatThrownBy(() -> watcher(100).finalizedSlot()).isInstanceOf(ChainUnavailableException.class);
    }

    /** performance-design.md: the reconciliation and the restore check have a quota of their own. */
    @Test
    void theReconciliationAndTheRestoreCheckHaveTheirOwnQuota() {
        RpcBudget budget = new RpcBudget(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 10);

        assertThat(drain(budget, ChainPort.Purpose.WITHDRAWALS)).isEqualTo(2);
        assertThat(drain(budget, ChainPort.Purpose.RECONCILIATION)).isEqualTo(2);
        assertThat(drain(budget, ChainPort.Purpose.RESTORE_CHECK)).isEqualTo(1);
        assertThat(drain(budget, ChainPort.Purpose.DEPOSITS)).isEqualTo(8);
    }

    @Test
    void aReconciliationNeverTakesTheBudgetOfAWithdrawal() {
        RpcChainWatcher tight = watcher(10);
        first.answer("getSlot", "5");
        second.answer("getSlot", "5");
        first.answer("getLatestBlockhash", "{\"value\":{\"blockhash\":\"H\",\"lastValidBlockHeight\":9}}");

        for (int i = 0; i < 4; i++) {
            tight.finalizedSlot();
        }

        assertThat(tight.latestBlockhash().blockhash()).isEqualTo("H");
        assertThatThrownBy(tight::finalizedSlot).isInstanceOf(ChainUnavailableException.class);
    }

    private static int drain(RpcBudget budget, ChainPort.Purpose purpose) {
        int count = 0;
        while (budget.tryAcquire("a", purpose)) {
            count++;
        }
        return count;
    }

    // ----- the reader of the reconciliation (U5-NFR4.3) -----

    @Test
    void theReaderReadsTheSnapshotAndAnEntryNewerThanTheCursor() {
        first.answer("getSignaturesForAddress", "[{\"signature\":\"SigDeposit\",\"err\":null}]");
        first.answer("getSlot", "88");
        first.answer("getMultipleAccounts", "{\"value\":[{\"lamports\":2000000000}]}");
        first.answerWhen("getTokenAccountsByOwner", RpcChainWatcher.TOKEN_PROGRAMS.get(0), tokenAccounts(MINT, "750"));
        first.answerWhen("getTokenAccountsByOwner", RpcChainWatcher.TOKEN_PROGRAMS.get(1), tokenAccounts());

        Optional<ChainReading> reading = new ReconciliationReader(watcher(100)).read(OWNER, "SigCursor");

        assertThat(reading).isPresent();
        assertThat(reading.get().unreadIncoming()).isTrue();
        assertThat(reading.get().snapshot().slot()).isEqualTo(88L);
        assertThat(reading.get().snapshot().lamports()).isEqualTo(2_000_000_000L);
        assertThat(reading.get().snapshot().tokens()).containsEntry(MINT, 750L);
        assertThat(first.params).anyMatch(p -> p.contains("\"until\":\"SigCursor\""));
    }

    @Test
    void theReaderSeesNothingNewWhenTheHistoryEndsAtTheCursor() {
        first.answer("getSignaturesForAddress", "[]");
        first.answer("getSlot", "88");
        first.answer("getMultipleAccounts", "{\"value\":[null]}");
        first.answer("getTokenAccountsByOwner", tokenAccounts());

        ChainReading reading =
                new ReconciliationReader(watcher(100)).read(OWNER, null).orElseThrow();

        assertThat(reading.unreadIncoming()).isFalse();
        assertThat(reading.snapshot().lamports()).isZero();
    }

    @Test
    void anyFailureOfTheRpcIsUnavailableForTheReader() {
        first.goDown();
        second.goDown();

        assertThat(new ReconciliationReader(watcher(100)).read(OWNER, "c")).isEmpty();
    }

    @Test
    void aJsonRpcErrorIsUnavailableForTheReaderToo() throws Exception {
        try (RpcStub refusing = new RpcStub()) {
            ChainPort refused = new ChainPort() {
                @Override
                public IncomingPage incomingSince(String address, String afterSignature, int limit) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Map<String, Long> balances(List<String> addresses) {
                    throw new SolanaRpcProvider.RpcRefusedException("refused");
                }

                @Override
                public Map<String, List<com.memetrade.botwallet.domain.ChainObservation>> checkSignatures(
                        List<String> signatures) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public RecentBlockhash latestBlockhash() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String send(String signedTransactionBase64) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public long rentExemptMinimum() {
                    return 0;
                }

                @Override
                public Map<String, Long> tokenBalances(String owner) {
                    return Map.of();
                }

                @Override
                public long finalizedSlot() {
                    return 1;
                }

                @Override
                public SignaturePage signaturesSince(
                        Purpose purpose, String address, String until, String before, int limit) {
                    return new SignaturePage(List.of());
                }

                @Override
                public List<String> transactionSigners(String signature) {
                    return List.of();
                }
            };

            assertThat(new ReconciliationReader(refused).read(OWNER, null)).isEmpty();
            assertThat(refusing.calls("getSlot")).isZero();
        }
    }
}
