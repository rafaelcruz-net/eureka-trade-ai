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
        attribution = attribution == null ? new Attribution(null) : attribution;
        read = read == null ? new Read(null) : read;
    }

    /** The defaults of {@code application.yml}, for the tests that build the readers without Spring. */
    public static PnlRealProperties defaults() {
        return new PnlRealProperties(null, null);
    }

    /**
     * @param gateAndCycleTimeout the hard deadline of the read of cost of the rounds of the barrier and of the REAL
     *     cycle (BR2.7: it may only go down without the founder): 50 ms to 250 ms, 250 ms by default
     */
    public record Attribution(Duration gateAndCycleTimeout) {

        public static final Duration DEFAULT_GATE_AND_CYCLE_TIMEOUT = Duration.ofMillis(250);

        public Attribution {
            gateAndCycleTimeout = gateAndCycleTimeout == null ? DEFAULT_GATE_AND_CYCLE_TIMEOUT : gateAndCycleTimeout;
            require(
                    gateAndCycleTimeout.compareTo(Duration.ofMillis(50)) >= 0
                            && gateAndCycleTimeout.compareTo(DEFAULT_GATE_AND_CYCLE_TIMEOUT) <= 0,
                    "memetrade.pnl.real.attribution.gate-and-cycle-timeout");
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
