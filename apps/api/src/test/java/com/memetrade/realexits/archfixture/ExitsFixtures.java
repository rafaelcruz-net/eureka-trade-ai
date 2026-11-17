package com.memetrade.realexits.archfixture;

import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.SwapSigningRequest;
import com.memetrade.botwallet.infra.RealProperties;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.realexits.infra.SimulatedTriggerAdapter;
import java.net.http.HttpClient;
import java.util.UUID;

/**
 * Test-only fixtures of the U7 (Step 1): each one breaks exactly one ArchUnit rule of {@code RealExitsArchitectureTest},
 * so the test proves the rule fails. Never part of the production code.
 */
public final class ExitsFixtures {

    private ExitsFixtures() {}

    /** Reads the internals of another module (the settings of the bot wallet) instead of its public interface. */
    public static final class ExitsInternalsFixture {
        public boolean enabled(RealProperties properties) {
            return properties.exits().enabled();
        }
    }

    /** Reaches the network outside {@code realexits.infra.jupiter}. */
    public static final class ExitsNetworkFixture {
        public HttpClient client() {
            return HttpClient.newHttpClient();
        }
    }

    /** Asks a signature of the bot wallet itself, outside the TxSigner. */
    public static final class ExitsSigningFixture {
        public Object sign(RealExecutionWallet wallets, SwapSigningRequest request) {
            return wallets.signSwap(UUID.randomUUID(), UUID.randomUUID(), request);
        }
    }

    /** Touches the PAPER execution. */
    public static final class ExitsPaperFixture {
        public Object paper(PaperExecution paper) {
            return paper;
        }
    }

    /** A production-like class that references the simulated adapter. */
    public static final class SimulatedReferenceFixture {
        public Object adapter() {
            return new SimulatedTriggerAdapter();
        }
    }
}
