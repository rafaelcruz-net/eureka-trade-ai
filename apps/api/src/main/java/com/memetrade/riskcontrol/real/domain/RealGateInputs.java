package com.memetrade.riskcontrol.real.domain;

import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.riskcontrol.api.OrderIntent;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 261113-trading-real-bot U3 (logical-components.md): the immutable photo of everything one evaluation uses. The
 * instant comes from the injectable clock of the caller; the policy has no clock of its own.
 *
 * @param wallet the state of the wallet of the person (M1); empty when there is none in that tenant
 * @param buy the inputs of a BUY; empty for a SELL, and for a BUY refused by the state or the source before them
 */
public record RealGateInputs(
        Instant now,
        RealRiskLimits limits,
        OrderIntent intent,
        Optional<RealStateView> wallet,
        SourceKind source,
        Optional<BuyInputs> buy) {

    public RealGateInputs {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(buy, "buy");
    }
}
