package com.memetrade.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U2, Etapa 6 (written before the code; BR1.1, BR1.2, F-1): the total order of the network
 * order key. The effective slot first; in the same slot the rows with a recorded slot before the inherited ones; among
 * the recorded ones the signature compared byte by byte; then createdAt, reference, the rank of the kind and the
 * idempotency key. The random entry id is never part of it, so the same rows give the same order in every read.
 */
class NetworkOrderKeyTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    private static NetworkOrderKey recorded(long slot, String signature, Instant at, String reference, int rank) {
        return new NetworkOrderKey(slot, false, Optional.of(signature), at, reference, rank, reference + ":" + rank);
    }

    private static NetworkOrderKey inherited(long slot, Instant at, String reference, int rank, String key) {
        return new NetworkOrderKey(slot, true, Optional.empty(), at, reference, rank, key);
    }

    @Test
    void theEffectiveSlotDecidesFirst() {
        NetworkOrderKey early = recorded(10, "zzzz", T0.plusSeconds(60), "order:b", 0);
        NetworkOrderKey late = recorded(11, "AAAA", T0, "order:a", 0);

        assertThat(early).isLessThan(late);
    }

    @Test
    void inTheSameSlotTheRecordedRowsComeBeforeTheInheritedOnes() {
        NetworkOrderKey inheritedEarlier = inherited(900, T0, "order:a", 0, "a");
        NetworkOrderKey recordedLater = recorded(900, "SigZ", T0.plusSeconds(3600), "order:z", 3);

        assertThat(recordedLater).isLessThan(inheritedEarlier);
    }

    @Test
    void amongRecordedRowsTheSmallerSignatureByBytesComesFirstWhateverTheRecordingOrder() {
        // 'B' (66) < 'a' (97) by bytes, although a case-insensitive collation would put "a" first (BR1.2, F-1).
        NetworkOrderKey upper = recorded(5, "Bxxx", T0.plusSeconds(10), "order:sell", 0);
        NetworkOrderKey lower = recorded(5, "axxx", T0, "order:buy", 0);

        assertThat(upper).isLessThan(lower);
    }

    @Test
    void theSameSignatureFollowsCreatedAtThenReferenceThenKindRankThenIdempotencyKey() {
        NetworkOrderKey fill = recorded(7, "Sig1", T0, "order:x", 0);
        NetworkOrderKey fee = recorded(7, "Sig1", T0, "order:x", 1);
        NetworkOrderKey rent = recorded(7, "Sig1", T0, "order:x", 2);
        NetworkOrderKey laterFill = recorded(7, "Sig1", T0.plusMillis(1), "order:x", 0);
        NetworkOrderKey otherReference = recorded(7, "Sig1", T0, "order:y", 0);

        List<NetworkOrderKey> keys = new ArrayList<>(List.of(laterFill, rent, otherReference, fee, fill));
        Collections.sort(keys);

        assertThat(keys).containsExactly(fill, fee, rent, otherReference, laterFill);
    }

    @Test
    void inheritedRowsFollowTheRecordingOrderAndTheKeyIsTheLastTieBreak() {
        NetworkOrderKey first = inherited(0, T0, "dep:a", 3, "k1");
        NetworkOrderKey second = inherited(0, T0, "dep:a", 3, "k2");
        NetworkOrderKey byReference = inherited(0, T0, "dep:B", 3, "k0");

        List<NetworkOrderKey> keys = new ArrayList<>(List.of(second, first, byReference));
        Collections.sort(keys);

        assertThat(keys).containsExactly(byReference, first, second);
    }

    @Test
    void theOrderIsTheSameForAnyShuffleOfTheSameRows() {
        List<NetworkOrderKey> rows = List.of(
                recorded(1, "Sa", T0, "order:1", 0),
                recorded(1, "Sb", T0, "order:2", 0),
                inherited(1, T0.minusSeconds(5), "order:3", 1, "c"),
                recorded(2, "Sa", T0, "order:4", 2),
                inherited(2, T0, "wd:1", 3, "d"),
                inherited(0, T0.minusSeconds(60), "dep:1", 3, "e"));
        List<NetworkOrderKey> sorted = new ArrayList<>(rows);
        Collections.sort(sorted);

        Random random = new Random(20261122L);
        for (int i = 0; i < 50; i++) {
            List<NetworkOrderKey> shuffled = new ArrayList<>(rows);
            Collections.shuffle(shuffled, random);
            Collections.sort(shuffled);
            assertThat(shuffled).as("shuffle %s", i).containsExactlyElementsOf(sorted);
        }
    }

    @Test
    void theRankOfEachKindIsTheOneOfTheLedger() {
        assertThat(NetworkOrderKey.kindRank(RealEntryKind.SWAP_FILL_IN)).isZero();
        assertThat(NetworkOrderKey.kindRank(RealEntryKind.SWAP_FILL_OUT)).isZero();
        assertThat(NetworkOrderKey.kindRank(RealEntryKind.NETWORK_FEE)).isEqualTo(1);
        assertThat(NetworkOrderKey.kindRank(RealEntryKind.RENT)).isEqualTo(2);
        for (RealEntryKind other : List.of(
                RealEntryKind.DEPOSIT,
                RealEntryKind.WITHDRAWAL,
                RealEntryKind.COMPENSATION,
                RealEntryKind.RESERVE,
                RealEntryKind.RESERVE_RELEASE)) {
            assertThat(NetworkOrderKey.kindRank(other)).as(other.name()).isEqualTo(3);
        }
    }

    @Test
    void aRecordedRowWithoutSignatureOrANegativeSlotIsRefused() {
        assertThatThrownBy(() -> new NetworkOrderKey(1, false, Optional.empty(), T0, "order:x", 0, "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NetworkOrderKey(-1, true, Optional.empty(), T0, "order:x", 0, "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NetworkOrderKey(1, true, Optional.empty(), T0, "order:x", 4, "k"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
