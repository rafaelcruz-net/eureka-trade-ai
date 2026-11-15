package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Jobs of the TxSigner (reliability-design.md "Trabalhos"): the hourly cleanup of used nonces and the daily check of
 * orphan keys (a bot wallet key with no policy, older than ten minutes: the creation failed after CreateKey). The
 * scheduling and the single run across instances (ShedLock on the TxSigner database) are in the infrastructure.
 */
public final class SignerJobs {

    static final int NONCE_BATCH = 1_000;

    private final Clock clock;
    private final KmsPort kms;
    private final SignerStore store;
    private final SignerEvents events;
    private final Duration orphanMinAge;

    public SignerJobs(Clock clock, KmsPort kms, SignerStore store, SignerEvents events, Duration orphanMinAge) {
        this.clock = clock;
        this.kms = kms;
        this.store = store;
        this.events = events;
        this.orphanMinAge = orphanMinAge;
    }

    public int purgeNonces() {
        Instant now = clock.instant();
        int total = 0;
        int purged;
        do {
            purged = store.purgeNonces(now, NONCE_BATCH);
            total += purged;
        } while (purged == NONCE_BATCH);
        return total;
    }

    /** Publishes one ORPHAN_KEY event per orphan key and returns how many were found. */
    public int reportOrphanKeys() {
        Instant now = clock.instant();
        int orphans = 0;
        for (String keyRef : kms.listKeyRefs()) {
            Optional<KeyTags> tags = KeyTags.from(kms.tags(keyRef));
            if (tags.isEmpty()
                    || Duration.between(kms.keyCreatedAt(keyRef), now).compareTo(orphanMinAge) < 0) {
                continue;
            }
            if (hasPolicy(tags.get())) {
                continue;
            }
            orphans++;
            events.publish(new SignerEvent(
                    SignerEvent.Type.ORPHAN_KEY,
                    Base58.encode(kms.publicKey(keyRef)),
                    null,
                    "ORPHAN_CHECK",
                    "KEY_WITHOUT_POLICY",
                    now));
        }
        return orphans;
    }

    private boolean hasPolicy(KeyTags tags) {
        try {
            return store.findPolicyByCreationRequest(UUID.fromString(tags.creationRequestId()))
                    .isPresent();
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }
}
