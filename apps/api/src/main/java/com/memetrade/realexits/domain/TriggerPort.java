package com.memetrade.realexits.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Port X1 of the Jupiter Trigger API (BR9.1, logical-components.md): create, cancel and read the conditionals of the
 * bot wallet. Two implementations: the simulated one (LOCALNET and the tests, never a transaction, BR0.3) and the real
 * one over the Trigger API v2, written only after the recording of BR0. A technical failure is
 * {@link TriggerUnavailable}: nothing changes state and the next run tries again (BR5.6, U7-NFR4.6).
 */
public interface TriggerPort {

    /** Which adapter this is; recorded on the conditional (the simulated one has no custody account). */
    AdapterKind kind();

    /**
     * BR1.2: how the main wallet authorizes the creation. The simulated adapter takes the signed consent of the
     * conditional ({@code signMessage}); the real one a transaction co-signed by the main wallet in the browser.
     */
    Authorization authorization();

    /** X1: the unsigned creation transaction (Base64) built by the Trigger API; only for {@link Authorization#TRANSACTION}. */
    String prepareCreate(CreateRequest request);

    /**
     * X1: opens the conditional once the authorization was checked by the server ({@code authorization} is the signed
     * transaction of the main wallet, or the signature of the consent for the simulated adapter).
     */
    CreateResult create(CreateRequest request, String authorization);

    /** X1: asks the cancellation of the conditional, signed only by the bot wallet in the TxSigner (BR1.3). */
    CancelResult cancel(OrderRef order);

    /** X1: what the Trigger API says of the conditional now. */
    ProviderStatus status(OrderRef order);

    enum Authorization {
        MESSAGE,
        TRANSACTION
    }

    /** What a creation needs; tenant and person never travel to the provider. */
    record CreateRequest(
            UUID conditionalOrderId,
            String botWalletAddress,
            String mainWalletAddress,
            String mint,
            ConditionalKind kind,
            Optional<BigDecimal> stopPriceUsd,
            Optional<BigDecimal> takeProfitPriceUsd,
            long amountBaseUnits,
            int slippageBps) {

        public CreateRequest {
            Objects.requireNonNull(conditionalOrderId, "conditionalOrderId");
            Objects.requireNonNull(botWalletAddress, "botWalletAddress");
            Objects.requireNonNull(mint, "mint");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(stopPriceUsd, "stopPriceUsd");
            Objects.requireNonNull(takeProfitPriceUsd, "takeProfitPriceUsd");
        }
    }

    /** A conditional already created: its id at the provider and the last cancellation sent, if any. */
    record OrderRef(
            UUID conditionalOrderId,
            String botWalletAddress,
            String mint,
            Optional<String> providerOrderId,
            Optional<String> cancelSignature) {

        public OrderRef {
            Objects.requireNonNull(conditionalOrderId, "conditionalOrderId");
            Objects.requireNonNull(mint, "mint");
            Objects.requireNonNull(providerOrderId, "providerOrderId");
            Objects.requireNonNull(cancelSignature, "cancelSignature");
        }
    }

    sealed interface CreateResult permits CreateResult.Opened, CreateResult.Rejected {

        /** The conditional is open at the provider, with the custody account and the creation signature, if any. */
        record Opened(String providerOrderId, Optional<String> createSignature, Optional<String> escrowAccount)
                implements CreateResult {
            public Opened {
                Objects.requireNonNull(providerOrderId, "providerOrderId");
                Objects.requireNonNull(createSignature, "createSignature");
                Objects.requireNonNull(escrowAccount, "escrowAccount");
            }
        }

        /** BR3.5, BR2.4: refused by the provider or by the policy of the TxSigner, with the reason. */
        record Rejected(String reason, boolean signingPolicy) implements CreateResult {
            public Rejected {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    sealed interface CancelResult permits CancelResult.Cancelled, CancelResult.Submitted {

        /** The cancellation is confirmed (on the network, finalized, for the real adapter). */
        record Cancelled(Optional<String> signature) implements CancelResult {
            public Cancelled {
                Objects.requireNonNull(signature, "signature");
            }
        }

        /** A cancellation was sent and is not confirmed yet; the job reads it again in the next run (BR6.4). */
        record Submitted(String signature) implements CancelResult {
            public Submitted {
                Objects.requireNonNull(signature, "signature");
            }
        }
    }

    sealed interface ProviderStatus
            permits ProviderStatus.Open, ProviderStatus.Filled, ProviderStatus.Cancelled, ProviderStatus.Expired {

        record Open() implements ProviderStatus {}

        /** BR5.2: the provider says it filled; the fill counts only after the network confirms the signature. */
        record Filled(ExitSide side, String fillSignature) implements ProviderStatus {
            public Filled {
                Objects.requireNonNull(side, "side");
                Objects.requireNonNull(fillSignature, "fillSignature");
            }
        }

        record Cancelled(Optional<String> signature) implements ProviderStatus {
            public Cancelled {
                Objects.requireNonNull(signature, "signature");
            }
        }

        record Expired() implements ProviderStatus {}
    }

    /** The provider (or the network behind it) did not answer; nothing changes state. */
    final class TriggerUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public TriggerUnavailable(String message) {
            super(message);
        }
    }
}
