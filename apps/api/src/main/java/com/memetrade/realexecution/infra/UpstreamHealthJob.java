package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.realexecution.domain.SwapRouter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BR9.1 (F9): every 30 s in the worker, the call of health of each provider (a small quote SOL to USDC on the Jupiter,
 * the block height on the RPC, an authenticated read on the TxSigner) and since when each one is healthy (the row of the
 * provider has the nil uuid as subject). An UPSTREAM pause of a person, from BR9.1 or BR5.2, is resumed when every
 * provider is healthy for 5 minutes and the person has no AMBIGUOUS order. The withdrawal never depends on it.
 */
@Component
public class UpstreamHealthJob {

    static final UUID PROVIDER_ROW = new UUID(0L, 0L);
    private static final Logger log = LoggerFactory.getLogger(UpstreamHealthJob.class);

    private final SwapRouter router;
    private final RealChain chain;
    private final RealExecutionWallet wallets;
    private final RealStateQuery states;
    private final RealPauses pauses;
    private final JdbcRealExecutionStore store;
    private final RealExecutionMetrics metrics;
    private final RealExecutionProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public UpstreamHealthJob(
            SwapRouter router,
            RealChain chain,
            RealExecutionWallet wallets,
            RealStateQuery states,
            RealPauses pauses,
            JdbcRealExecutionStore store,
            RealExecutionMetrics metrics,
            RealExecutionConfig.CheckedSettings settings,
            JdbcClient jdbc,
            Clock clock,
            PlatformTransactionManager manager) {
        this.router = router;
        this.chain = chain;
        this.wallets = wallets;
        this.states = states;
        this.pauses = pauses;
        this.store = store;
        this.metrics = metrics;
        this.properties = settings.properties();
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public int runOnce() {
        Instant now = clock.instant();
        Map<RealExecutionMetrics.Provider, Boolean> healthy = new EnumMap<>(RealExecutionMetrics.Provider.class);
        healthy.put(RealExecutionMetrics.Provider.JUPITER, probe(() -> router.probe()));
        healthy.put(RealExecutionMetrics.Provider.RPC, probe(chain::blockHeight));
        healthy.put(RealExecutionMetrics.Provider.TXSIGNER, wallets.signerReachable());
        boolean allHealthyLongEnough = true;
        for (Map.Entry<RealExecutionMetrics.Provider, Boolean> provider : healthy.entrySet()) {
            Instant since = record(provider.getKey(), provider.getValue(), now);
            allHealthyLongEnough &= since != null
                    && !since.plus(properties.upstreamHealthyFor()).isAfter(now);
        }
        int resumed = 0;
        if (allHealthyLongEnough) {
            for (RealStateQuery.PausedWallet paused : states.pausedWallets(RealPauseReason.UPSTREAM)) {
                var wallet = paused.wallet();
                if (store.hasAmbiguous(wallet.tenantId(), wallet.userId())) {
                    continue;
                }
                boolean done = Boolean.TRUE.equals(transactions.execute(
                        status -> pauses.resumeUser(wallet.tenantId(), wallet.userId(), RealPauseReason.UPSTREAM)
                                == RealPauses.PauseOutcome.RESUMED));
                if (done) {
                    resumed++;
                }
            }
        }
        metrics.jobRun(RealExecutionMetrics.Job.HEALTH);
        return resumed;
    }

    private static boolean probe(Runnable call) {
        try {
            call.run();
            return true;
        } catch (com.memetrade.realexecution.domain.NoRouteException answered) {
            // The provider answered (a refusal of the probe route is not an outage, review Major 1).
            return true;
        } catch (RuntimeException down) {
            log.warn("REAL provider unhealthy: error={}", down.getClass().getSimpleName());
            return false;
        }
    }

    /** Records since when the provider is healthy (kept while healthy, cleared when not); returns that instant. */
    private Instant record(RealExecutionMetrics.Provider provider, boolean healthy, Instant now) {
        if (!healthy) {
            metrics.upstreamFailure(provider);
        }
        Timestamp since = jdbc.sql("""
                        insert into real_upstream_health (subject_id, provider, consecutive_failures, healthy_since,
                          updated_at)
                        values (:subject, :provider, 0, :since, :now)
                        on conflict (subject_id, provider) do update set
                          healthy_since = case when :healthy then coalesce(real_upstream_health.healthy_since, :now)
                            else null end,
                          updated_at = excluded.updated_at
                        returning healthy_since""")
                .param("subject", PROVIDER_ROW)
                .param("provider", provider.name())
                .param("since", healthy ? Timestamp.from(now) : null)
                .param("now", Timestamp.from(now))
                .param("healthy", healthy)
                .query(Timestamp.class)
                .optional()
                .orElse(null);
        return since == null ? null : since.toInstant();
    }
}
