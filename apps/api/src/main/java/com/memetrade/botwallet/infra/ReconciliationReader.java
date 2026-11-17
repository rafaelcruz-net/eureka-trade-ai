package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.api.EscrowBalances;
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

    /**
     * 261113-trading-real-bot U7 (BR5.3b, U7-NFR4.8): the base units held by the custody accounts of the conditionals
     * of the Jupiter OPEN or CANCELLING, by mint, read on the network through the account recorded at the creation.
     * Empty (unreadable) when an account was not recorded, belongs to another mint, or the RPC failed: the
     * reconciliation is then UNAVAILABLE, never DIVERGENT. Logs never carry the account.
     */
    public Optional<Map<String, Long>> readEscrows(List<EscrowBalances.Escrow> escrows) {
        Map<String, Long> held = new java.util.TreeMap<>();
        try {
            for (EscrowBalances.Escrow escrow : escrows) {
                if (escrow.account().isEmpty()) {
                    log.warn("A custody account of an open conditional was not recorded; the wallet is not compared");
                    return Optional.empty();
                }
                ChainPort.TokenAccountBalance balance =
                        chain.tokenAccountBalance(escrow.account().get());
                if (!balance.mint().equals(escrow.mint())) {
                    log.warn("A custody account holds another mint than its conditional; the wallet is not compared");
                    return Optional.empty();
                }
                held.merge(escrow.mint(), balance.baseUnits(), Math::addExact);
            }
            return Optional.of(held);
        } catch (RuntimeException unavailable) {
            log.warn(
                    "A custody account was not read for a reconciliation: {}",
                    unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
