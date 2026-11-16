package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.MemetradeApplication;
import com.memetrade.bots.application.BotCommandService;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.CycleAuthContext;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.U5Bots;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Correção do ponta a ponta (CI do commit 605a8fb, smoke): the REAL cycle of the worker rolled back on EVERY slot with
 * {@code DataIntegrityViolationException}, because {@code bot_cycle.outcome} (varchar(12), CHECK of migration 0010)
 * did not accept the REAL outcomes of the U4 ({@code REAL_RECORDED} has 13 characters) nor {@code skip_reason} the
 * {@code REAL_UNAVAILABLE} skip. Every earlier test of the REAL path called the steps below the cycle, never the cycle
 * against the real migrations.
 *
 * <p>This context is the worker of the REAL Compose with the recorded swap: profiles {@code test,worker}, REAL on, the
 * {@code RECORDED_*} values of a synthetic mint, the real migrations on PostgreSQL, the real barrier, ledger, market
 * query and assessment, and the test strategy of the recorded swap wired by Spring, exactly as in the Compose. The
 * cycle runs through {@link CycleRunner#run}, the call of the slot dispatcher. Scheduling is off; nothing calls the
 * network (the cycle only records the decision; the TxSigner and the Jupiter are reached later by the order driver).
 */
@Tag("u4-real-execution")
@SpringBootTest(
        classes = MemetradeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "memetrade.process=worker",
            "memetrade.scheduling.enabled=false",
            "memetrade.real.enabled=true",
            "MEMETRADE_TXSIGNER_URL=http://txsigner:8443",
            "MEMETRADE_TXSIGNER_TOKEN=cycle-test-token-not-a-secret",
            "memetrade.real.test-swap.decimals=6",
            "memetrade.real.test-swap.buy-lamports=50000000",
            "memetrade.real.test-swap.expected-out-units=35728672"
        })
@ActiveProfiles({"test", "worker"})
class RecordedSwapCycleIT {

    /** A synthetic mint per run of the class: never a real one (team.md "Determinismo"). */
    private static final String MINT = SyntheticIds.mint();

    private static final String WSOL = "So11111111111111111111111111111111111111112";
    private static final String SOURCE = "u4-swap-cycle";
    private static final long SOL = 1_000_000_000L;

    static {
        AbstractPostgresIT.sharedJdbcUrl();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractPostgresIT::sharedJdbcUrl);
        registry.add("spring.datasource.username", AbstractPostgresIT.APP::user);
        registry.add("spring.datasource.password", AbstractPostgresIT.APP::password);
        registry.add("memetrade.real.test-swap.mint", () -> MINT);
    }

    @Autowired
    private CycleRunner cycles;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RealLedger ledger;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private BotCommandService commands;

    @Autowired
    private JdbcBotRepository bots;

    @Autowired
    private CycleAuthContext cycleAuth;

    @Autowired
    private Clock clock;

    @Autowired(required = false)
    private RecordedSwapStrategy strategy;

    private record Person(Principal principal, UUID botWalletId) {}

    /** A released person with an ACTIVE bot wallet in AUTO_FULL (cap 5 SOL) and, optionally, a deposit. */
    private Person person(long depositLamports) {
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        UUID botWalletId = UUID.randomUUID();
        Instant created = clock.instant().minus(Duration.ofDays(2));
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", seeded.tenantId())
                .param("user", seeded.userId())
                .param("address", SyntheticIds.mint())
                .param("main", seeded.walletAddress())
                .param("at", Timestamp.from(created))
                .update();
        jdbc.sql("""
                        insert into real_preferences (bot_wallet_id, operation_mode, cap_lamports, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, updated_at)
                        values (:id, 'AUTO_FULL', :cap, 30000000, 100000, :at)""")
                .param("id", botWalletId)
                .param("cap", 5 * SOL)
                .param("at", Timestamp.from(created))
                .update();
        Instant requested = clock.instant().minus(Duration.ofHours(25));
        jdbc.sql("""
                        insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at,
                          state)
                        values (:id, :user, 'LOCALNET', :user, :requested, :effective, 'ACTIVE')""")
                .param("id", UUID.randomUUID())
                .param("user", seeded.userId())
                .param("requested", Timestamp.from(requested))
                .param("effective", Timestamp.from(requested.plus(Duration.ofHours(24))))
                .update();
        if (depositLamports > 0) {
            String key = "dep:" + UUID.randomUUID();
            transactions.executeWithoutResult(status -> ledger.record(new RealEntry(
                    seeded.tenantId(), botWalletId, RealEntryKind.DEPOSIT, depositLamports, null, key, key)));
        }
        return new Person(new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER), botWalletId);
    }

    /** Fresh market data of the recorded mint (assessable) and of the wrapped SOL, as sim-a publishes them. */
    private void freshMarket() {
        jdbc.sql("insert into data_source (source_id, name, simulated, status) values (:id, 'Swap gravado (teste)',"
                        + " true, 'OK') on conflict do nothing")
                .param("id", SOURCE)
                .update();
        jdbc.sql("update data_source set enabled = true, status = 'OK', failure_category = null where source_id = :id")
                .param("id", SOURCE)
                .update();
        observe(
                MINT,
                6,
                Map.of(
                        "PRICE", "0.10",
                        "LIQUIDITY", "2500000",
                        "VOLUME_24H", "8000000",
                        "HOLDERS", "250000"));
        observe(WSOL, 9, Map.of("PRICE", "72.558", "LIQUIDITY", "2500000", "VOLUME_24H", "8000000"));
    }

    private void observe(String mint, int decimals, Map<String, String> fields) {
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into token (chain, mint, symbol, decimals, first_seen_at)
                        values ('solana', :mint, 'TST', :decimals, :at) on conflict do nothing""")
                .param("mint", mint)
                .param("decimals", decimals)
                .param("at", Timestamp.from(now.minus(Duration.ofDays(30))))
                .update();
        fields.forEach((field, value) -> jdbc.sql("""
                        insert into market_observation (observation_id, chain, mint, source_id, field, status,
                          value, observed_at)
                        values (:id, 'solana', :mint, :source, :field, 'KNOWN', :value, :at)
                        on conflict (chain, mint, source_id, field)
                        do update set value = excluded.value, observed_at = excluded.observed_at""")
                .param("id", UUID.randomUUID())
                .param("mint", mint)
                .param("source", SOURCE)
                .param("field", field)
                .param("value", new BigDecimal(value))
                .param("at", Timestamp.from(now))
                .update());
    }

    /** The REAL bot of the recorded mint, through the real commands (create REAL, the wizard steps and start). */
    private UUID recordedMintBot(Person person) {
        U5Bots fixtures = new U5Bots(commands, bots, cycleAuth::as);
        Principal owner = person.principal();
        String body = fixtures.as(
                        owner,
                        () -> commands.create(
                                owner,
                                "MOMENTUM_VOLUME",
                                "Swap gravado",
                                "REAL",
                                new IdempotencyKey(UUID.randomUUID())))
                .body();
        UUID botId = UUID.fromString(body.replaceAll(".*\"botId\":\"([0-9a-f-]{36})\".*", "$1"));
        fixtures.saveSteps(owner, botId, new TokenId("solana", MINT), U5Bots.defaultLimits(), "1000.000000");
        fixtures.as(owner, () -> commands.start(owner, botId, new IdempotencyKey(UUID.randomUUID())));
        return botId;
    }

    private Map<String, Object> cycleRow(UUID botId, Instant slot) {
        return jdbc.sql("select status, outcome, skip_reason, failure_cause from bot_cycle"
                        + " where bot_id = :bot and slot_start = :slot")
                .param("bot", botId)
                .param("slot", Timestamp.from(slot))
                .query()
                .singleRow();
    }

    private long pendingBuys(UUID botId) {
        return jdbc.sql("select count(*) from real_decision_request where bot_id = :bot and side = 'BUY'"
                        + " and input_lamports = 50000000 and mint = :mint")
                .param("bot", botId)
                .param("mint", MINT)
                .query(Long.class)
                .single();
    }

    @Test
    void theStrategyOfTheRecordedSwapIsWiredInTheWorkerOfTheRealCompose() {
        assertThat(strategy).isNotNull();
    }

    @Test
    void theCycleOfTheRecordedMintBotRecordsOneBuyOfTheRecordedLamportsAndNeverAnother() {
        freshMarket();
        Person person = person(3 * SOL);
        UUID botId = recordedMintBot(person);
        Instant slot = SlotDispatcher.slotOf(clock.instant());
        BotRef ref = new BotRef(botId, TenantScope.of(person.principal()));

        CycleResult first = cycles.run(ref, slot);

        assertThat(cycleRow(botId, slot))
                .containsEntry("status", "COMPLETED")
                .containsEntry("outcome", "REAL_RECORDED");
        assertThat(first.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(pendingBuys(botId)).isEqualTo(1);
        // 261113-trading-real-bot U5 (BR7.1, S25): the decision of the REAL bot is recorded REAL.
        assertThat(jdbc.sql("select distinct mode from decision where bot_id = :b")
                        .param("b", botId)
                        .query(String.class)
                        .list())
                .containsExactly("REAL");

        freshMarket();
        Instant next = slot.plus(Duration.ofMinutes(5));
        CycleResult second = cycles.run(ref, next);

        assertThat(second.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(cycleRow(botId, next)).containsEntry("outcome", "NO_ORDER");
        assertThat(pendingBuys(botId)).isEqualTo(1);
    }

    /**
     * Correção do ponta a ponta: stopping a REAL bot called the PAPER close of positions, and a REAL bot has no PAPER
     * account ("No PAPER account for bot"), so the stop failed and the bot kept cycling. It stops now, without any
     * PAPER row (U4-NFR2.1); its REAL positions are sold by "vender tudo e sacar".
     */
    @Test
    void aRealBotStopsWithoutThePaperLedger() {
        freshMarket();
        Person person = person(3 * SOL);
        UUID botId = recordedMintBot(person);
        cycles.run(new BotRef(botId, TenantScope.of(person.principal())), SlotDispatcher.slotOf(clock.instant()));
        U5Bots fixtures = new U5Bots(commands, bots, cycleAuth::as);

        fixtures.as(
                person.principal(),
                () -> commands.stop(person.principal(), botId, new IdempotencyKey(UUID.randomUUID())));

        assertThat(jdbc.sql("select state from bot where bot_id = :bot")
                        .param("bot", botId)
                        .query(String.class)
                        .single())
                .isEqualTo("STOPPED");
        assertThat(jdbc.sql("select count(*) from paper_account where bot_id = :bot")
                        .param("bot", botId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** Every outcome and skip the REAL path of the cycle can write is accepted by the schema (0020). */
    @Test
    void everyRealOutcomeAndSkipOfTheCycleFitsTheSchema() {
        freshMarket();
        Person person = person(0);
        UUID botId = recordedMintBot(person);
        Instant slot = SlotDispatcher.slotOf(clock.instant());
        cycles.run(new BotRef(botId, TenantScope.of(person.principal())), slot);
        java.util.List<String> results = java.util.List.of(
                "RECORDED", "ORDER_IN_PROGRESS", "LIQUIDATION_ACTIVE", "BOT_NOT_OWNED", "REJECTED", "UNAVAILABLE");

        for (String result : results) {
            assertThat(jdbc.sql("update bot_cycle set outcome = :outcome where bot_id = :bot and slot_start = :slot")
                            .param("outcome", "REAL_" + result)
                            .param("bot", botId)
                            .param("slot", Timestamp.from(slot))
                            .update())
                    .isEqualTo(1);
        }
        assertThat(jdbc.sql("update bot_cycle set status = 'SKIPPED', outcome = null, skip_reason = 'REAL_UNAVAILABLE'"
                                + " where bot_id = :bot and slot_start = :slot")
                        .param("bot", botId)
                        .param("slot", Timestamp.from(slot))
                        .update())
                .isEqualTo(1);
    }

    @Test
    void aBuyTheBarrierRefusesIsAnOutcomeOfTheCycleAndTheNextSlotTriesAgain() {
        freshMarket();
        Person person = person(0);
        UUID botId = recordedMintBot(person);
        Instant slot = SlotDispatcher.slotOf(clock.instant());

        CycleResult result = cycles.run(new BotRef(botId, TenantScope.of(person.principal())), slot);

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(cycleRow(botId, slot))
                .containsEntry("status", "COMPLETED")
                .containsEntry("outcome", "REAL_REJECTED");
        assertThat(pendingBuys(botId)).isZero();

        String key = "dep:" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> ledger.record(new RealEntry(
                person.principal().tenantId(), person.botWalletId(), RealEntryKind.DEPOSIT, 3 * SOL, null, key, key)));
        freshMarket();
        Instant next = slot.plus(Duration.ofMinutes(5));
        cycles.run(new BotRef(botId, TenantScope.of(person.principal())), next);

        assertThat(cycleRow(botId, next)).containsEntry("outcome", "REAL_RECORDED");
        assertThat(pendingBuys(botId)).isEqualTo(1);
    }
}
