package com.memetrade.pnl.application;

import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.pnl.modeview.ModeBlockExecutor;
import com.memetrade.pnl.real.RealPnlResult;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.jdbc.ReadDeadline;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * BR6.2 of the 261113-trading-real-bot, changed by the U2 of 261121-paper-real-unico (BR10.3, NFR1.2, NFR1.16;
 * performance-design.md "Leitura em fluxo do P&L REAL"): the P&L REAL of {@code GET /api/v1/real/pnl} is the total and
 * the positions of {@link RealBotPnl} (realized GROSS, positions with the one average cost), read through the pool
 * {@code pnl-mode-block} like the REAL block of C4 (mercado REAL, the same deadline of 800 ms), so at most
 * {@code pool-size} REAL reads run at once. A full queue, a deadline or any failure is {@link RealPnlUnavailable}; the
 * route answers its technical error. Never anything PAPER.
 */
@Service
@ProcessRole(ProcessRole.Kind.API)
public class RealPnlService {

    private final RealBotPnl pnl;
    private final ModeBlockExecutor blocks;
    private final Duration blockTimeout;

    public RealPnlService(RealBotPnl pnl, ModeBlockExecutor blocks, PnlByModeProperties properties) {
        this.pnl = pnl;
        this.blocks = blocks;
        this.blockTimeout = properties.blockTimeout();
    }

    /** The total and the positions of the person, in its own read transaction (outside the pool). */
    public RealPnlResult pnl(UUID tenantId, UUID userId) {
        return pnl.realPnl(tenantId, userId);
    }

    /** The same, through the pool of the blocks, within the deadline of a block. */
    public RealPnlResult pnlThroughThePool(UUID tenantId, UUID userId) {
        long deadline = System.nanoTime() + blockTimeout.toNanos();
        CompletableFuture<RealPnlResult> block =
                blocks.submit(PnlMarket.REAL, deadline, () -> pnl.realPnl(tenantId, userId));
        try {
            return block.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            block.cancel(false);
            throw new RealPnlUnavailable(RealPnlUnavailable.TIMEOUT, late);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RealPnlUnavailable unavailable) {
                throw unavailable;
            }
            if (cause instanceof ModeBlockExecutor.DeadlineExpired || ReadDeadline.isTimeout(cause)) {
                throw new RealPnlUnavailable(RealPnlUnavailable.TIMEOUT, cause);
            }
            throw new RealPnlUnavailable(
                    cause == null ? "Unknown" : cause.getClass().getSimpleName(), cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RealPnlUnavailable(interrupted.getClass().getSimpleName(), interrupted);
        }
    }
}
