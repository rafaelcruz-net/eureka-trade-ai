package com.memetrade.botwallet.infra;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.memetrade.botwallet.domain.Network;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U1, Step 15 (BR10.2, NFR3.8; security-design.md "Checagem de rede no RealTestSeeder"): the
 * test seed of the REAL release acts only on the LOCALNET. On the DEVNET and the MAINNET, with the seed on, it throws
 * (the process does not start) and writes nothing.
 */
class RealTestSeederNetworkTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void onTheLocalnetTheSeedWritesTheReleaseAndTheTerms() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        RealTestSeeder seeder = new RealTestSeeder(jdbc, CLOCK, real(Network.LOCALNET), "TestWallet");

        assertThatCode(() -> seeder.seedFor(new RealTestSeeder.Person(UUID.randomUUID(), UUID.randomUUID())))
                .doesNotThrowAnyException();

        verify(jdbc, atLeastOnce()).sql(argThat(sql -> sql.contains("insert into real_release")));
        verify(jdbc, atLeastOnce()).sql(argThat(sql -> sql.contains("insert into terms_acceptance")));
    }

    @ParameterizedTest
    @EnumSource(
            value = Network.class,
            names = {"DEVNET", "MAINNET"})
    void outsideTheLocalnetTheSeedThrowsAndWritesNothing(Network network) {
        JdbcClient jdbc = mock(JdbcClient.class);
        RealTestSeeder seeder = new RealTestSeeder(jdbc, CLOCK, real(network), "TestWallet");

        assertThatThrownBy(seeder::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCALNET");
        assertThatThrownBy(() -> seeder.seedFor(new RealTestSeeder.Person(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void withTheSeedOffNothingIsCheckedNorWritten() {
        JdbcClient jdbc = mock(JdbcClient.class);
        RealProperties off = new RealProperties(
                false, "v3", Network.MAINNET, 1L, 1L, 1L, 1L, 1L, 1, 0L, false, null, null, null, null, null);

        assertThatCode(() -> new RealTestSeeder(jdbc, CLOCK, off, "TestWallet").seed())
                .doesNotThrowAnyException();
        verifyNoInteractions(jdbc);
    }

    private static RealProperties real(Network network) {
        return new RealProperties(false, "v3", network, 1L, 1L, 1L, 1L, 1L, 1, 0L, true, null, null, null, null, null);
    }
}
