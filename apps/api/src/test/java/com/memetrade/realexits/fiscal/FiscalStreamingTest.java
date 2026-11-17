package com.memetrade.realexits.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealEntryRecord;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.UnknownReason;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 9 of the U7, written BEFORE the code (U7-NFR3.3, BR7.10): the export writes line by line while the ledger is
 * read: the first line is in the output before the ledger hands the last entry; nothing is kept in memory nor stored.
 */
@Tag("u7-exits-and-fiscal")
class FiscalStreamingTest {

    private final RealLedger ledger = mock(RealLedger.class);
    private final RealStateQuery states = mock(RealStateQuery.class);
    private final MarketDataQuery market = mock(MarketDataQuery.class);
    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);
    private final Principal person = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);

    @Test
    @SuppressWarnings("unchecked")
    void theFirstLineIsWrittenBeforeTheLedgerHandsTheLastEntry() {
        UUID wallet = UUID.randomUUID();
        when(states.botWalletIds(person.tenantId(), person.userId())).thenReturn(List.of(wallet));
        when(market.decimals(any())).thenReturn(new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.empty()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicReference<String> beforeTheLast = new AtomicReference<>();
        doAnswer(call -> {
                    Consumer<RealEntryRecord> consumer = call.getArgument(4);
                    consumer.accept(deposit("dep:first", 1_000L));
                    consumer.accept(deposit("dep:second", 2_000L));
                    beforeTheLast.set(out.toString(StandardCharsets.UTF_8));
                    consumer.accept(deposit("dep:last", 3_000L));
                    return null;
                })
                .when(ledger)
                .streamEntries(eq(person.tenantId()), eq(List.of(wallet)), any(), any(), any(Consumer.class));
        FiscalExportService service = new FiscalExportService(
                ledger,
                states,
                market,
                audit,
                Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC),
                mock(org.springframework.transaction.PlatformTransactionManager.class));

        long rows = service.export(person, period(), out);

        assertThat(beforeTheLast.get()).contains(";DEPOSITO;").contains("0,000001000");
        assertThat(rows).isEqualTo(3L);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("0,000003000");
    }

    @Test
    void withoutABotWalletTheFileHasOnlyTheHeader() {
        when(states.botWalletIds(person.tenantId(), person.userId())).thenReturn(List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FiscalExportService service = new FiscalExportService(
                ledger,
                states,
                market,
                audit,
                Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC),
                mock(org.springframework.transaction.PlatformTransactionManager.class));

        assertThat(service.export(person, period(), out)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).endsWith("assinatura\r\n");
    }

    private static FiscalPeriod period() {
        return FiscalPeriod.of(
                Optional.of(LocalDate.parse("2026-11-01")),
                Optional.of(LocalDate.parse("2026-11-17")),
                ZoneId.of("UTC"),
                LocalDate.parse("2026-11-17"));
    }

    private static RealEntryRecord deposit(String reference, long lamports) {
        return new RealEntryRecord(
                RealEntryKind.DEPOSIT,
                lamports,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                reference,
                Instant.parse("2026-11-10T10:00:00Z"));
    }
}
