package com.memetrade.pnl.loadtest;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.loadtest.LoadtestCycleAuthContext;
import com.memetrade.shared.config.ProcessRole;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE (which starts only together with "test"): the seed of the load scenario
 * {@code pnl-by-mode} of the 261121-paper-real-unico U2 (NFR1.6, NFR1.14). It waits for {@code people} people to sign in
 * (the BETA_USER principals of validated sessions seen by {@link LoadtestCycleAuthContext}: tenant and person never come
 * from anywhere else), then gives each one 5 PAPER bots and 5 REAL bots with 730 days of history, 4 orders per bot per
 * day (2 buys and 2 sells), written straight in the tables the reads use:
 *
 * <ul>
 *   <li>PAPER: an account per bot and one closed day of {@code pnl_daily} per bot and day (4 fills);
 *   <li>REAL: one bot wallet, the settled orders and their ledger rows as the confirmations write them, with the mix of
 *       NFR1.6: 5% of own sales with excess, 2% of liquidations, 2% of conditionals (half partial), 1% of orders FAILED
 *       on chain, 10% of orders in the mode Assinada, 1% of buys whose order is gone, at least 10 pairs of orders in the
 *       same slot, and no row in {@code real_entry_slot} in the first 365 days (SLOT_MISSING).
 * </ul>
 *
 * Synthetic mints, signatures and wallets only; nothing is signed nor sent, nothing calls a network. The progress is
 * reported by the health component {@code loadtestPnl}. Not named "Seeder": that suffix is reserved by the U3 rule to
 * the seeders of the {@code test} profile.
 */
@Component
@Profile("loadtest")
@ProcessRole(ProcessRole.Kind.API)
public class LoadtestPnlSeed {

    private static final Logger log = LoggerFactory.getLogger(LoadtestPnlSeed.class);
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    static final int DAYS = 730;
    static final int BOTS_PER_MODE = 5;
    private static final int BATCH = 500;
    private static final Duration WAIT_FOR_PEOPLE = Duration.ofMinutes(30);

    /** The state of the seed, read by the health component. */
    public enum State {
        OFF,
        WAITING,
        SEEDING,
        UP,
        FAILED
    }

    private final JdbcTemplate jdbc;
    private final LoadtestCycleAuthContext people;
    private final Clock clock;
    private final int expected;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<State> state = new AtomicReference<>(State.OFF);
    private final AtomicInteger seeded = new AtomicInteger();
    private final AtomicReference<String> failure = new AtomicReference<>("");

    public LoadtestPnlSeed(
            JdbcTemplate jdbc,
            LoadtestCycleAuthContext people,
            Clock clock,
            @Value("${memetrade.loadtest.pnl.people:0}") int expected) {
        this.jdbc = jdbc;
        this.people = people;
        this.clock = clock;
        this.expected = expected;
    }

    public State state() {
        return state.get();
    }

    public int seeded() {
        return seeded.get();
    }

    public int expected() {
        return expected;
    }

    public String failure() {
        return failure.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (expected <= 0) {
            return;
        }
        state.set(State.WAITING);
        Thread thread = new Thread(this::run, "loadtest-pnl-seed");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            Instant until = clock.instant().plus(WAIT_FOR_PEOPLE);
            while (people.people().size() < expected) {
                if (!clock.instant().isBefore(until)) {
                    throw new IllegalStateException("fewer than " + expected + " people signed in within 30 minutes");
                }
                Thread.sleep(2_000);
            }
            state.set(State.SEEDING);
            for (Principal person : people.people().subList(0, expected)) {
                seed(person);
                seeded.incrementAndGet();
                log.info("loadtest pnl seed: person {} of {} seeded", seeded.get(), expected);
            }
            state.set(State.UP);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(interrupted);
        } catch (RuntimeException failed) {
            fail(failed);
        }
    }

    private void fail(Exception cause) {
        failure.set(cause.getClass().getSimpleName());
        state.set(State.FAILED);
        log.error("loadtest pnl seed failed: {}", cause.getClass().getSimpleName());
    }

    /** The 10 bots of one person and their history. */
    void seed(Principal person) {
        jdbc.update(
                "insert into bot_owner (tenant_id, owner_user_id, created_at) values (?, ?, now()) on conflict do nothing",
                person.tenantId(),
                person.userId());
        LocalDate first = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(DAYS);
        for (int b = 0; b < BOTS_PER_MODE; b++) {
            paperBot(person, b, first);
        }
        UUID wallet = UUID.randomUUID();
        jdbc.update(
                """
                insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address, main_wallet_address,
                  network, status, created_at)
                values (?, ?, ?, ?, ?, 'LOCALNET', 'ACTIVE', ?)""",
                wallet,
                person.tenantId(),
                person.userId(),
                address(),
                address(),
                Timestamp.from(first.atStartOfDay().toInstant(ZoneOffset.UTC)));
        List<UUID> bots = new ArrayList<>();
        for (int b = 0; b < BOTS_PER_MODE; b++) {
            bots.add(bot(person, "Real " + (b + 1), "REAL", first.plusDays(b)));
        }
        RealHistory history = new RealHistory(person, wallet, bots, first);
        for (int day = 0; day < DAYS; day++) {
            history.day(day);
        }
        history.flush();
    }

    private void paperBot(Principal person, int index, LocalDate first) {
        UUID botId = bot(person, "Simulado " + (index + 1), "PAPER", first.plusDays(index));
        jdbc.update(
                """
                insert into paper_account (bot_id, tenant_id, owner_user_id, mode, status, created_at)
                values (?, ?, ?, 'PAPER', 'OPEN', ?)""",
                botId,
                person.tenantId(),
                person.userId(),
                Timestamp.from(first.atStartOfDay().toInstant(ZoneOffset.UTC)));
        jdbc.update("""
                insert into pnl_daily (tenant_id, owner_user_id, bot_id, mode, chain, token_mint, utc_day, realized, fees,
                  fill_count, updated_at)
                select ?, ?, ?, 'PAPER', 'solana', ?, cast(? as date) + g, ((g % 7) - 3) * 0.25, 0.04, 4, now()
                from generate_series(0, ? - 1) g""", person.tenantId(), person.userId(), botId, "LoadMintPaper" + index, first.toString(), DAYS);
    }

    private UUID bot(Principal person, String name, String market, LocalDate created) {
        UUID botId = UUID.randomUUID();
        jdbc.update(
                """
                insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state, created_at,
                  updated_at, market)
                values (?, ?, ?, ?, 'MOMENTUM_VOLUME', '1', 'ACTIVE', ?, ?, ?)""",
                botId,
                person.tenantId(),
                person.userId(),
                name,
                Timestamp.from(created.atStartOfDay().toInstant(ZoneOffset.UTC)),
                Timestamp.from(created.atStartOfDay().toInstant(ZoneOffset.UTC)),
                market);
        return botId;
    }

    /** A synthetic Base58 text of 44 characters, the shape of an address; never a real wallet. */
    private String address() {
        StringBuilder text = new StringBuilder(44);
        for (int i = 0; i < 44; i++) {
            text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return text.toString();
    }

    /** A synthetic Base58 signature of 88 characters. */
    private String signature() {
        StringBuilder text = new StringBuilder(88);
        for (int i = 0; i < 88; i++) {
            text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return text.toString();
    }

    /** The REAL history of one person, written in batches of {@value #BATCH} rows. */
    private final class RealHistory {
        private final Principal person;
        private final UUID wallet;
        private final List<UUID> bots;
        private final LocalDate first;
        private final String mint;
        private final List<Object[]> orders = new ArrayList<>();
        private final List<Object[]> rows = new ArrayList<>();
        private final List<Object[]> slots = new ArrayList<>();
        private long slot = 100_000_000L;
        private int sales;
        private int sameSlotPairs;

        RealHistory(Principal person, UUID wallet, List<UUID> bots, LocalDate first) {
            this.person = person;
            this.wallet = wallet;
            this.bots = bots;
            this.first = first;
            this.mint = address();
        }

        void day(int day) {
            Instant start = first.plusDays(day).atStartOfDay().toInstant(ZoneOffset.UTC);
            boolean recorded = day >= 365;
            boolean signed = day % 10 == 3;
            for (int b = 0; b < bots.size(); b++) {
                UUID bot = bots.get(b);
                Instant at = start.plus(Duration.ofMinutes(10L * b));
                boolean failedBuy = day % 100 == 7 && b == 0;
                boolean purgedBuy = day % 100 == 11 && b == 1;
                buy(bot, at, failedBuy, purgedBuy, signed, recorded);
                boolean sameSlot = recorded && b == 0 && sameSlotPairs < 12 && day % 30 == 0;
                if (sameSlot) {
                    sameSlotPairs++;
                }
                buy(bot, at.plusSeconds(60), false, false, signed, recorded);
                sale(bot, at.plusSeconds(3_600), day, b, false, signed, recorded, sameSlot);
                sale(bot, at.plusSeconds(7_200), day, b, true, signed, recorded, false);
            }
            if (orders.size() >= BATCH || rows.size() >= BATCH) {
                flush();
            }
        }

        private void buy(UUID bot, Instant at, boolean failed, boolean purged, boolean signed, boolean recorded) {
            UUID order = UUID.randomUUID();
            String sig = signature();
            long paid = 1_000_000L + (at.getEpochSecond() % 97) * 1_000L;
            long fee = 5_000L;
            if (!purged) {
                order(
                        order,
                        bot,
                        null,
                        "BUY",
                        paid,
                        null,
                        failed ? "FAILED" : "CONFIRMED",
                        sig,
                        1_000L,
                        fee,
                        signed ? "MAIN_WALLET" : "BOT_WALLET",
                        at);
            }
            if (!failed) {
                row("SWAP_FILL_IN", -paid, mint, 1_000L, sig, "order:" + order, order + ":SWAP_FILL_IN", at);
            }
            if (!signed) {
                row("NETWORK_FEE", -fee, null, null, sig, "order:" + order, order + ":NETWORK_FEE", at);
            }
            slot(sig, recorded, false);
        }

        private void sale(
                UUID bot,
                Instant at,
                int day,
                int index,
                boolean second,
                boolean signed,
                boolean recorded,
                boolean sameSlotAsBuy) {
            sales++;
            String sig = signature();
            long fee = 5_000L;
            long received = 1_050_000L + (at.getEpochSecond() % 89) * 1_000L;
            boolean conditional = second && day % 50 == 25 && index == 2;
            boolean liquidation = second && day % 50 == 0 && index == 3;
            long units = sales % 20 == 0 ? 1_100L : 1_000L;
            if (conditional) {
                units = day % 100 == 25 ? 500L : 1_000L;
                UUID id = UUID.randomUUID();
                row("SWAP_FILL_OUT", received, mint, units, sig, "conditional:" + id, "cond:" + sig + ":fill", at);
                row("NETWORK_FEE", -fee, null, null, sig, "conditional:" + id, "cond:" + sig + ":fee", at);
                slot(sig, recorded, sameSlotAsBuy);
                return;
            }
            UUID order = UUID.randomUUID();
            order(
                    order,
                    liquidation ? null : bot,
                    liquidation ? UUID.randomUUID() : null,
                    "SELL",
                    null,
                    units,
                    "CONFIRMED",
                    sig,
                    received,
                    fee,
                    signed ? "MAIN_WALLET" : "BOT_WALLET",
                    at);
            row("SWAP_FILL_OUT", received, mint, units, sig, "order:" + order, order + ":SWAP_FILL_OUT", at);
            if (!signed) {
                row("NETWORK_FEE", -fee, null, null, sig, "order:" + order, order + ":NETWORK_FEE", at);
            }
            slot(sig, recorded, sameSlotAsBuy);
        }

        private void order(
                UUID order,
                UUID bot,
                UUID liquidation,
                String side,
                Long lamports,
                Long units,
                String state,
                String sig,
                long out,
                long fee,
                String payer,
                Instant at) {
            orders.add(new Object[] {
                order,
                person.tenantId(),
                person.userId(),
                wallet,
                bot,
                liquidation,
                bot == null ? "USER_LIQUIDATION" : "BOT",
                "loadtest-pnl:" + order,
                side,
                mint,
                lamports,
                units,
                state,
                "FAILED".equals(state) ? "CHAIN_REJECTED" : null,
                sig,
                out,
                fee,
                payer,
                Timestamp.from(at),
                Timestamp.from(at)
            });
        }

        private void row(
                String kind,
                long lamports,
                String rowMint,
                Long units,
                String sig,
                String ref,
                String key,
                Instant at) {
            rows.add(new Object[] {
                UUID.randomUUID(),
                person.tenantId(),
                wallet,
                kind,
                lamports,
                rowMint,
                units,
                sig,
                ref,
                key,
                Timestamp.from(at)
            });
        }

        /** The first 365 days have no slot (SLOT_MISSING); a pair in the same slot reuses the slot of the buy. */
        private void slot(String sig, boolean recorded, boolean sameAsPrevious) {
            if (!recorded) {
                return;
            }
            if (!sameAsPrevious) {
                slot++;
            }
            slots.add(new Object[] {sig, slot, Timestamp.from(clock.instant())});
        }

        void flush() {
            if (!orders.isEmpty()) {
                jdbc.batchUpdate("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_wallet_id, bot_id, liquidation_id,
                          origin, idempotency_key, side, mint, input_lamports, input_base_units, operation_mode,
                          signing_mode, max_slippage_bps, reason, state, failure_code, tx_signature, out_base_units,
                          fee_lamports, fee_payer, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTO_FULL', 'AUTO_FULL', 100, 'loadtest', ?, ?, ?,
                          ?, ?, ?, ?, ?)""", orders);
                orders.clear();
            }
            if (!rows.isEmpty()) {
                jdbc.batchUpdate("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports, mint,
                          token_base_units, tx_signature, reference, idempotency_key, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
                rows.clear();
            }
            if (!slots.isEmpty()) {
                jdbc.batchUpdate(
                        "insert into real_entry_slot (tx_signature, slot, recorded_at) values (?, ?, ?)", slots);
                slots.clear();
            }
        }
    }
}
