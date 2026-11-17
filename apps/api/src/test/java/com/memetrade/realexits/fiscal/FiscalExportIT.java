package com.memetrade.realexits.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.realexits.AbstractRealExitsIT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Step 9 of the U7, written BEFORE the code (BR7, S28, S30 to S35, S45, U7-NFR1.6, U7-NFR3.3, U7-NFR3.8, U7-NFR3.9,
 * U7-NFR4.12): {@code GET /api/v1/real/exports/fiscal} writes the CSV of the REAL ledger of the person of the token, in
 * a stream, never stored; PAPER never enters; at most 10 exports per hour; it keeps working with the kill switch on,
 * without a release in force and with the REAL paused.
 */
@Tag("u7-exits-and-fiscal")
class FiscalExportIT extends AbstractRealExitsIT {

    private static final String ROUTE = "/api/v1/real/exports/fiscal";
    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    private RealKillSwitchState killSwitch;

    private MockHttpServletResponse export(Person person, String query) throws Exception {
        MvcResult started = mvc.perform(get(ROUTE + query).header("Authorization", person.bearer()))
                .andReturn();
        if (started.getRequest().isAsyncStarted()) {
            return mvc.perform(asyncDispatch(started)).andReturn().getResponse();
        }
        return started.getResponse();
    }

    private static String period() {
        LocalDate today = LocalDate.ofInstant(com.memetrade.testsupport.MutableClock.DEFAULT_START, ZoneOffset.UTC);
        return "?from=" + today.minusDays(30) + "&to=" + today + "&tz=America/Sao_Paulo";
    }

    private static List<String> lines(MockHttpServletResponse response) {
        String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        return Arrays.asList(csv.substring(1).split("\r\n"));
    }

    /** S28: deposit, buy and a conditional sale of the person, one line each, with the BOM and the header. */
    @Test
    void s28TheCsvHasTheBomTheHeaderAndOneLinePerOperation() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        String fill = "conditional:" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> ledger.record(new RealEntry(
                signed.setup().tenantId(),
                signed.setup().botWalletId(),
                RealEntryKind.SWAP_FILL_OUT,
                1_200_000L,
                null,
                fill,
                fill + ":fill",
                signed.setup().mint(),
                5_000L)));

        MockHttpServletResponse response = export(signed.person(), period());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition"))
                .startsWith("attachment")
                .contains("historico-real-");
        byte[] bytes = response.getContentAsByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        List<String> lines = lines(response);
        assertThat(lines.get(0))
                .isEqualTo("data_utc;data_local;tipo;mint;simbolo;quantidade;preco_sol;valor_sol;taxa_sol;assinatura");
        assertThat(lines.subList(1, lines.size()))
                .extracting(line -> line.split(";", -1)[2])
                .containsExactlyInAnyOrder("DEPOSITO", "COMPRA", "VENDA_CONDICIONAL");
        assertThat(lines).anySatisfy(line -> assertThat(line).contains(";DEPOSITO;;;;;3,000000000;;"));
    }

    /** S30, S33, U7-NFR1.6: only the person of the token; never PAPER; never an address of the person. */
    @Test
    void s30AndS33OnlyTheRealLedgerOfThePersonAndNoAddress() throws Exception {
        Signed mine = signedRealBot("AUTO_EXITS", 5_000L);
        Signed other = signedRealBot("AUTO_EXITS", 7_000L);

        List<String> lines = lines(export(mine.person(), period()));

        assertThat(lines).hasSize(3);
        assertThat(String.join("\n", lines))
                .doesNotContain(other.setup().mint())
                .doesNotContain(mine.person().wallet().address())
                .doesNotContain(mine.setup().bot().address());
        assertThat(jdbc.sql("select count(*) from ledger_entry where tenant_id = :t")
                        .param("t", mine.setup().tenantId())
                        .query(Long.class)
                        .single())
                .as("this person has no PAPER entry to leak either")
                .isZero();
    }

    /** S31: more than one year, or an inverted period, is 422. */
    @Test
    void s31APeriodLongerThanOneYearOrInvertedIsRefused() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 0L);

        MockHttpServletResponse tooLong =
                export(signed.person(), "?from=2026-01-01&to=2027-01-02&tz=America/Sao_Paulo");
        MockHttpServletResponse inverted = export(signed.person(), "?from=2026-05-02&to=2026-05-01");
        MockHttpServletResponse badZone = export(signed.person(), "?tz=Mars/Olympus");

        assertThat(tooLong.getStatus()).isEqualTo(422);
        assertThat(body(tooLong).path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(inverted.getStatus()).isEqualTo(422);
        assertThat(badZone.getStatus()).isEqualTo(422);
    }

    /** S32: the 11th export in the hour is 429 with Retry-After. */
    @Test
    void s32TheEleventhExportInTheHourIsRateLimited() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 0L);
        for (int export = 0; export < 10; export++) {
            assertThat(export(signed.person(), period()).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse limited = export(signed.person(), period());

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(body(limited).path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
    }

    /** S34, U7-NFR4.12: with the kill switch on, without a release in force and with the REAL paused. */
    @Test
    void s34TheExportWorksWithTheKillSwitchWithoutReleaseAndPaused() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        killSwitch.activate(ADMIN, "teste da exportação");
        try {
            assertThat(export(signed.person(), period()).getStatus()).isEqualTo(200);
        } finally {
            killSwitch.deactivate(ADMIN, "fim do teste");
        }
        jdbc.sql("update real_preferences set paused_reasons = array['USER'] where bot_wallet_id = :w")
                .param("w", signed.setup().botWalletId())
                .update();
        assertThat(export(signed.person(), period()).getStatus()).isEqualTo(200);
        jdbc.sql("update real_release set revoked_at = now() where user_id = :u")
                .param("u", signed.setup().userId())
                .update();
        assertThat(export(signed.person(), period()).getStatus()).isEqualTo(200);
    }

    /** S45 on the database: the sum of BR7.6 over the exported lines equals the variation of the SOL balance. */
    @Test
    void s45TheSumOfTheExportedLinesIsTheVariationOfTheBalanceInThePeriod() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);

        List<String> lines = lines(export(signed.person(), period()));
        long sum = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] field = line.split(";", -1);
            long value = lamports(field[7]);
            long fee = lamports(field[8]);
            sum += switch (field[2]) {
                case "DEPOSITO", "VENDA", "VENDA_CONDICIONAL", "AJUSTE_ENTRADA" -> value - fee;
                default -> -value - fee;
            };
        }
        long variation = jdbc.sql("select coalesce(sum(lamports), 0) from real_ledger_entry where bot_wallet_id = :w"
                        + " and entry_kind not in ('RESERVE', 'RESERVE_RELEASE')")
                .param("w", signed.setup().botWalletId())
                .query(Long.class)
                .single();

        assertThat(sum).isEqualTo(variation);
    }

    /** U7-NFR3.3: 20 thousand entries end in at most 10 s, written as a stream. */
    @Test
    void twentyThousandEntriesAreExportedInAtMostTenSeconds() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 0L);
        jdbc.sql("""
                        insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                          reference, idempotency_key, created_at)
                        select gen_random_uuid(), :t, :w, 'DEPOSIT', 1000 + n, 'dep:bulk-' || n || '-' || :salt,
                          'dep:bulk-' || n || '-' || :salt, cast(:at as timestamptz) - (n || ' seconds')::interval
                        from generate_series(1, 20000) as n""")
                .param("t", signed.setup().tenantId())
                .param("w", signed.setup().botWalletId())
                .param("salt", UUID.randomUUID().toString())
                .param("at", java.sql.Timestamp.from(clock.instant()))
                .update();
        long started = System.nanoTime();

        MockHttpServletResponse response = export(signed.person(), period());

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThanOrEqualTo(Duration.ofSeconds(10));
        assertThat(lines(response)).hasSizeGreaterThan(20_000);
    }

    /** BR7.10: the export is audited with the period and the count, never the content. */
    @Test
    void theExportIsAuditedWithoutItsContent() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);

        export(signed.person(), period());

        String details = jdbc.sql("select payload::text from audit_outbox where payload->>'action' ="
                        + " 'REAL_FISCAL_EXPORTED' and payload->>'tenantId' = :t order by created_at desc limit 1")
                .param("t", signed.setup().tenantId().toString())
                .query(String.class)
                .single();
        assertThat(details)
                .contains("rows")
                .doesNotContain(signed.setup().mint())
                .doesNotContain("COMPRA");
    }

    private static long lamports(String solWithComma) {
        if (solWithComma.isEmpty()) {
            return 0;
        }
        return new java.math.BigDecimal(solWithComma.replace(',', '.'))
                .movePointRight(9)
                .longValueExact();
    }
}
