package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.ChainPort;
import com.memetrade.botwallet.domain.ChainReading;
import com.memetrade.botwallet.domain.ChainSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (BR2.1, performance-design.md, U5-NFR3.1, U5-NFR4.3): the read of the network for one
 * reconciliation through the ChainPort of the U1, in commitment finalized: one signature newer than the cursor of the
 * deposit scan (an entry not yet registered), the finalized slot, the lamports of the system account and the token
 * accounts (Token and Token-2022). Any failure of the RPC is "unavailable" (empty), never a divergence. Logs never carry
 * the address.
 */
@Component
public class ReconciliationReader {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationReader.class);

    private final ChainPort chain;

    public ReconciliationReader(ChainPort chain) {
        this.chain = chain;
    }

    public Optional<ChainReading> read(String address, String depositCursor) {
        try {
            boolean unread = !chain.signaturesSince(ChainPort.Purpose.RECONCILIATION, address, depositCursor, null, 1)
                    .signatures()
                    .isEmpty();
            long slot = chain.finalizedSlot();
            Map<String, Long> lamports = chain.balances(List.of(address));
            Map<String, Long> tokens = chain.tokenBalances(address);
            return Optional.of(
                    new ChainReading(unread, new ChainSnapshot(slot, lamports.getOrDefault(address, 0L), tokens)));
        } catch (RuntimeException unavailable) {
            log.warn(
                    "The network was not read for a reconciliation: {}",
                    unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
