package com.memetrade.bots.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.TokenId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 2 (written before the code; ENT-409, BR1.5, BR1.6, BR4.2, AC6.1.3, NFR2.6; E5, E7):
 * the inputs of the decision of a REAL bot with the flag on. The exits are evaluated on the own available quantity, at
 * the own average cost; a mint of the wallet without an own available quantity leaves the tokens of the request
 * (heldNotSellable) with its note; the set that blocks a buy stays the set of mints of the wallet.
 */
class OwnDecisionInputsTest {

    private static final String XYZ = "XyZ1111111111111111111111111111111111111111";
    private static final String ABC = "AbC1111111111111111111111111111111111111111";
    private static final String QRS = "QrS1111111111111111111111111111111111111111";

    @Test
    void e5TheExitIsOnTheAvailableQuantityAtTheOwnAverageCost() {
        // Own 100 XYZ for 100,000,000 lamports, 60 XYZ in a pending sale: 40 XYZ at floor(100e6 x 40e6 / 100e6).
        OwnDecisionInputs inputs = OwnDecisionInputs.of(
                List.of(XYZ),
                new OwnHoldings.Known(List.of(new OwnPosition(XYZ, 100_000_000L, 100_000_000L, 40_000_000L))));

        assertThat(inputs.ownPositions()).containsExactly(new OwnDecisionInputs.OwnExit(XYZ, 40_000_000L, 40_000_000L));
        assertThat(inputs.heldNotSellable()).isEmpty();
        assertThat(inputs.notes()).containsExactly(Map.entry(XYZ, OwnQuantityNote.OWN_QUANTITY_AVAILABLE));
    }

    @Test
    void theCostOfAPartialExitIsTheFloorOfTheProportion() {
        // 7 lamports over 3 units, 2 available: floor(7 x 2 / 3) = 4.
        OwnDecisionInputs inputs =
                OwnDecisionInputs.of(List.of(XYZ), new OwnHoldings.Known(List.of(new OwnPosition(XYZ, 3, 7, 2))));

        assertThat(inputs.ownPositions()).containsExactly(new OwnDecisionInputs.OwnExit(XYZ, 2, 4));
    }

    @Test
    void e7AMintOfTheWalletWithoutOwnQuantityLeavesTheRequestWithItsNote() {
        // Only B bought XYZ; A holds ABC of its own.
        OwnDecisionInputs inputs = OwnDecisionInputs.of(
                List.of(XYZ, ABC), new OwnHoldings.Known(List.of(new OwnPosition(ABC, 10, 10, 10))));

        assertThat(inputs.heldNotSellable()).containsExactly(XYZ);
        assertThat(inputs.notes())
                .containsEntry(XYZ, OwnQuantityNote.NO_OWN_QUANTITY)
                .containsEntry(ABC, OwnQuantityNote.OWN_QUANTITY_AVAILABLE);
        assertThat(inputs.noOwnQuantityMints()).containsExactly(XYZ);
        assertThat(inputs.unavailableReason()).isEmpty();
    }

    @Test
    void anUnavailableReadPutsEveryMintOfTheWalletInHeldNotSellable() {
        OwnDecisionInputs inputs =
                OwnDecisionInputs.of(List.of(XYZ, ABC), new OwnHoldings.Unavailable(OwnQuantityReason.BUDGET_EXCEEDED));

        assertThat(inputs.ownPositions()).isEmpty();
        assertThat(inputs.heldNotSellable()).containsExactlyInAnyOrder(XYZ, ABC);
        assertThat(inputs.notes().values()).containsOnly(OwnQuantityNote.OWN_QUANTITY_UNAVAILABLE);
        assertThat(inputs.unavailableReason()).contains(OwnQuantityReason.BUDGET_EXCEEDED);
        assertThat(inputs.noOwnQuantityMints()).isEmpty();
    }

    @Test
    void aBotThatRunsWithoutTheReadIsUnavailableReadFailed() {
        OwnDecisionInputs inputs = OwnDecisionInputs.of(List.of(XYZ), new OwnHoldings.NotRead());

        assertThat(inputs.heldNotSellable()).containsExactly(XYZ);
        assertThat(inputs.unavailableReason()).contains(OwnQuantityReason.READ_FAILED);
    }

    @Test
    void ownPositionsAndHeldNotSellableSplitTheMintsOfTheWalletExactly() {
        List<String> wallet = List.of(XYZ, ABC, QRS);
        OwnDecisionInputs inputs = OwnDecisionInputs.of(
                wallet,
                new OwnHoldings.Known(List.of(
                        new OwnPosition(XYZ, 5, 5, 5),
                        new OwnPosition(ABC, 5, 5, 0),
                        // a mint the wallet no longer holds (sold meanwhile) is ignored
                        new OwnPosition("Gone111111111111111111111111111111111111111", 9, 9, 9))));

        Set<String> exits = new HashSet<>();
        inputs.ownPositions().forEach(exit -> exits.add(exit.mint()));
        Set<String> union = new HashSet<>(exits);
        union.addAll(inputs.heldNotSellable());
        Set<String> intersection = new HashSet<>(exits);
        intersection.retainAll(inputs.heldNotSellable());

        assertThat(union).containsExactlyInAnyOrderElementsOf(wallet);
        assertThat(intersection).isEmpty();
    }

    @Test
    void theTokensOfTheRequestAreTodaysMinusHeldNotSellable() {
        OwnDecisionInputs inputs = OwnDecisionInputs.of(
                List.of(XYZ, ABC), new OwnHoldings.Known(List.of(new OwnPosition(ABC, 10, 10, 10))));
        TokenId xyz = new TokenId("solana", XYZ);
        TokenId abc = new TokenId("solana", ABC);
        TokenId selected = new TokenId("solana", QRS);

        assertThat(inputs.requestTokens(List.of(selected, xyz, abc))).containsExactly(selected, abc);
    }

    @Test
    void aPositionThatBreaksItsInvariantsIsRefused() {
        assertThatThrownBy(() -> new OwnPosition(XYZ, 5, 5, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OwnPosition(XYZ, 0, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OwnPosition(XYZ, -1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
