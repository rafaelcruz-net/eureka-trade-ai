package com.memetrade.botwallet.infra.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.botwallet.domain.ChainObservation;
import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.ChainUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 17, written after the code (NFR3.7, NFR4.11, reliability-design.md): the ChainWatcher reads finalized data,
 * batches balances and statuses, switches provider when one is down, opens a circuit per provider, keeps the 80/20
 * budget, and gives FAILED only the observations of both providers. Recorded answers, loopback only.
 */
class ChainWatcherTest {

    private static final String BOT = "Bot1111111111111111111111111111111111111111";
    private static final String MAIN = "Main111111111111111111111111111111111111111";

    private RpcStub first;
    private RpcStub second;
    private RpcChainWatcher chain;

    @BeforeEach
    void start() throws Exception {
        first = new RpcStub();
        second = new RpcStub();
        chain = watcher(100);
    }

    private RpcChainWatcher watcher(int maxRps) {
        HttpClient http = HttpClient.newHttpClient();
        return new RpcChainWatcher(
                List.of(
                        new SolanaRpcProvider("a", first.uri(), http, Duration.ofSeconds(3)),
                        new SolanaRpcProvider("b", second.uri(), http, Duration.ofSeconds(3))),
                new RpcBudget(Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC), maxRps));
    }

    @AfterEach
    void stop() {
        first.close();
        second.close();
    }

    @Test
    void balancesComeFromOneBatchedCallAndAMissingAccountIsZero() {
        first.answer("getMultipleAccounts", "{\"value\":[{\"lamports\":1500},null]}");

        Map<String, Long> balances = chain.balances(List.of(BOT, MAIN));

        assertThat(balances).containsEntry(BOT, 1500L).containsEntry(MAIN, 0L);
        assertThat(first.calls("getMultipleAccounts")).isEqualTo(1);
    }

    @Test
    void incomingTransfersAreReadOldestFirstAndFailedOrOutgoingOnesAreSkipped() {
        first.answer(
                        "getSignaturesForAddress",
                        "[{\"signature\":\"s3\",\"err\":null},{\"signature\":\"s2\",\"err\":{\"x\":1}},"
                                + "{\"signature\":\"s1\",\"err\":null}]")
                .answer(
                        "getTransaction",
                        "{\"blockTime\":1763121600,\"transaction\":{\"message\":{\"instructions\":[{\"program\":"
                                + "\"system\",\"parsed\":{\"type\":\"transfer\",\"info\":{\"source\":\"" + MAIN
                                + "\",\"destination\":\"" + BOT + "\",\"lamports\":1000000000}}}]}}}");

        ChainPort.IncomingPage page = chain.incomingSince(BOT, "s0", 100);

        assertThat(page.transfers())
                .extracting(ChainPort.IncomingTransfer::txSignature)
                .containsExactly("s1", "s3");
        assertThat(page.transfers().get(0).lamports()).isEqualTo(1_000_000_000L);
        assertThat(page.transfers().get(0).sourceAddress()).isEqualTo(MAIN);
        assertThat(page.lastSignature()).isEqualTo("s3");
    }

    @Test
    void anOutgoingTransferIsNotADepositButMovesTheCursor() {
        first.answer("getSignaturesForAddress", "[{\"signature\":\"out1\",\"err\":null}]")
                .answer(
                        "getTransaction",
                        "{\"blockTime\":1,\"transaction\":{\"message\":{\"instructions\":[{\"program\":\"system\","
                                + "\"parsed\":{\"type\":\"transfer\",\"info\":{\"source\":\"" + BOT
                                + "\",\"destination\":\"" + MAIN + "\",\"lamports\":5}}}]}}}");

        ChainPort.IncomingPage page = chain.incomingSince(BOT, null, 100);

        assertThat(page.transfers()).isEmpty();
        assertThat(page.lastSignature()).isEqualTo("out1");
    }

    @Test
    void whenTheFirstProviderIsDownTheSecondAnswers() {
        first.goDown();
        second.answer("getLatestBlockhash", "{\"value\":{\"blockhash\":\"Hash1\",\"lastValidBlockHeight\":150}}");

        ChainPort.RecentBlockhash blockhash = chain.latestBlockhash();

        assertThat(blockhash).isEqualTo(new ChainPort.RecentBlockhash("Hash1", 150L));
    }

    @Test
    void withBothProvidersDownTheWorkerWaits() {
        first.goDown();
        second.goDown();

        assertThatThrownBy(() -> chain.send("AQID")).isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    void aSignatureIsCheckedOnBothProvidersWithTheirFinalizedHeights() {
        first.answer("getBlockHeight", "200").answer("getSignatureStatuses", "{\"value\":[null]}");
        second.answer("getBlockHeight", "199")
                .answer("getSignatureStatuses", "{\"value\":[{\"confirmationStatus\":\"finalized\",\"err\":null}]}")
                .answer("getTransaction", "{\"meta\":{\"fee\":5000}}");

        List<ChainObservation> observations =
                chain.checkSignatures(List.of("sigW")).get("sigW");

        assertThat(observations)
                .containsExactly(ChainObservation.notFound(200L), ChainObservation.finalized("sigW", 5000L));
    }

    @Test
    void aProviderThatDoesNotAnswerGivesAnUnavailableObservationNeverANotFound() {
        first.goDown();
        second.answer("getBlockHeight", "300").answer("getSignatureStatuses", "{\"value\":[null]}");

        List<ChainObservation> observations =
                chain.checkSignatures(List.of("sigX")).get("sigX");

        assertThat(observations.get(0).status()).isEqualTo(ChainObservation.Status.UNAVAILABLE);
        assertThat(observations.get(1)).isEqualTo(ChainObservation.notFound(300L));
    }

    @Test
    void repeatedTechnicalFailuresOpenTheCircuitOfThatProviderOnly() {
        first.goDown();
        second.answer("getMinimumBalanceForRentExemption", "890880");
        HttpClient http = HttpClient.newHttpClient();
        SolanaRpcProvider failing = new SolanaRpcProvider("a", first.uri(), http, Duration.ofSeconds(3));
        SolanaRpcProvider healthy = new SolanaRpcProvider("b", second.uri(), http, Duration.ofSeconds(3));
        RpcChainWatcher watcher =
                new RpcChainWatcher(List.of(failing, healthy), new RpcBudget(Clock.systemUTC(), 1000));

        for (int i = 0; i < 6; i++) {
            assertThat(watcher.rentExemptMinimum()).isEqualTo(890_880L);
        }

        assertThat(failing.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(healthy.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void theBudgetGivesWithdrawalsTwentyPercentOfEachProvider() {
        RpcBudget budget = new RpcBudget(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 10);

        int withdrawals = 0;
        while (budget.tryAcquire("a", ChainPort.Purpose.WITHDRAWALS)) {
            withdrawals++;
        }
        int deposits = 0;
        while (budget.tryAcquire("a", ChainPort.Purpose.DEPOSITS)) {
            deposits++;
        }

        assertThat(withdrawals).isEqualTo(2);
        assertThat(deposits).isEqualTo(8);
    }

    @Test
    void aSendOverBudgetGoesToTheOtherProvider() {
        RpcChainWatcher tight = watcher(1);
        second.answer("sendTransaction", "\"SigSent\"");
        first.answer("sendTransaction", "\"SigFirst\"");

        assertThat(tight.send("AQID")).isEqualTo("SigFirst");
        assertThat(tight.send("AQID")).isEqualTo("SigSent");
    }
}
