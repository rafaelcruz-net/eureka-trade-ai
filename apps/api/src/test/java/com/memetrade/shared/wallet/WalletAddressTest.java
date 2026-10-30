package com.memetrade.shared.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.SyntheticIds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class WalletAddressTest {

    private final String address = SyntheticIds.mint();

    @Test
    void toStringIsMaskedToFourPlusFourCharacters() {
        WalletAddress wallet = WalletAddress.of(address);

        assertThat(wallet.toString())
                .isEqualTo(address.substring(0, 4) + "…" + address.substring(address.length() - 4))
                .doesNotContain(address);
        assertThat(String.valueOf(wallet)).isEqualTo(wallet.masked());
    }

    @Test
    void fullValueOnlyThroughTheExplicitMethod() {
        assertThat(WalletAddress.of(address).full()).isEqualTo(address);
    }

    @Test
    void rejectsValuesThatAreNotBase58Addresses() {
        assertThatThrownBy(() -> WalletAddress.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletAddress.of("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletAddress.of("0" + address.substring(1)))
                .as("0 is outside the Base58 alphabet")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletAddress.of(address + "abcdefghijkmn"))
                .as("longer than 44 characters")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exceptionMessagesNeverEchoTheRejectedValue() {
        assertThatThrownBy(() -> WalletAddress.of("0" + address.substring(1)))
                .hasMessageNotContaining(address.substring(1));
    }
}
