package com.memetrade.pnl.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * 261121-paper-real-unico U2 (NFR1.4, NFR1.12, NFR1.16, NFR8.6; performance-design.md): the settings of the REAL P&L
 * read under {@code memetrade.pnl.real}, with their defaults in {@code application.yml} only (no variable of a task). A
 * value out of its domain stops the process with a message naming the property, never the value.
 *
 * @param attribution the budgets of the read with attribution
 * @param read the memory of the sorts of the reads that order the whole history
 */
@Validated
@ConfigurationProperties("memetrade.pnl.real")
public record PnlRealProperties(Attribution attribution, Read read) {

    public PnlRealProperties {
        attribution = attribution == null ? new Attribution(null, null) : attribution;
        read = read == null ? new Read(null) : read;
    }

    /** The defaults of {@code application.yml}, for the tests that build the readers without Spring. */
    public static PnlRealProperties defaults() {
        return new PnlRealProperties(null, null);
    }

    /**
     * @param gateAndCycleTimeout the hard deadline of the read of cost of the rounds of the barrier and of the REAL
     *     cycle (BR2.7: it may only go down without the founder): 50 ms to 250 ms, 250 ms by default
     * @param pnlTimeout the hard deadline of the attribution of the REAL block of the P&L (steps 1 and 3 of F1),
     *     inside the deadline of the block (D-NFR1): 50 ms to 500 ms, 500 ms by default, and below
     *     {@code memetrade.pnl.by-mode.block-timeout} (checked by {@code RealBotPnl})
     */
    public record Attribution(Duration gateAndCycleTimeout, Duration pnlTimeout) {

        public static final Duration DEFAULT_GATE_AND_CYCLE_TIMEOUT = Duration.ofMillis(250);
        public static final Duration DEFAULT_PNL_TIMEOUT = Duration.ofMillis(500);

        public Attribution {
            gateAndCycleTimeout = gateAndCycleTimeout == null ? DEFAULT_GATE_AND_CYCLE_TIMEOUT : gateAndCycleTimeout;
            require(
                    gateAndCycleTimeout.compareTo(Duration.ofMillis(50)) >= 0
                            && gateAndCycleTimeout.compareTo(DEFAULT_GATE_AND_CYCLE_TIMEOUT) <= 0,
                    "memetrade.pnl.real.attribution.gate-and-cycle-timeout");
            pnlTimeout = pnlTimeout == null ? DEFAULT_PNL_TIMEOUT : pnlTimeout;
            require(
                    pnlTimeout.compareTo(Duration.ofMillis(50)) >= 0 && pnlTimeout.compareTo(DEFAULT_PNL_TIMEOUT) <= 0,
                    "memetrade.pnl.real.attribution.pnl-timeout");
        }
    }

    /**
     * @param workMem the local {@code work_mem} of the reads in network order of the whole history (R-04): 1 MB to 12
     *     MB, 12 MB by default
     */
    public record Read(DataSize workMem) {

        public static final DataSize DEFAULT_WORK_MEM = DataSize.ofMegabytes(12);

        public Read {
            workMem = workMem == null ? DEFAULT_WORK_MEM : workMem;
            require(
                    workMem.compareTo(DataSize.ofMegabytes(1)) >= 0 && workMem.compareTo(DEFAULT_WORK_MEM) <= 0,
                    "memetrade.pnl.real.read.work-mem");
        }

        /** The value of {@code set local work_mem}, in kilobytes. */
        public String workMemSetting() {
            return workMem.toKilobytes() + "kB";
        }
    }

    private static void require(boolean condition, String property) {
        if (!condition) {
            throw new IllegalArgumentException(property + " is out of its domain");
        }
    }
}
