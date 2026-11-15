package com.memetrade.riskcontrol.real.domain;

import com.memetrade.riskcontrol.api.RealRejectionCode;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 261113-trading-real-bot U3: what the pure policy decided and what the gate must write with it, in the cycle
 * transaction: the DAILY_LOSS pause (BR4.3) and the photo of the day of a first buy (BR4.4).
 *
 * @param code {@code null} when approved
 * @param detail {@code null} when approved
 * @param figures the numbers of the failed check (empty when approved)
 * @param detailMint the mint of the open position of POSITION_PRICE_UNKNOWN
 */
public record RealVerdict(
        boolean approved,
        RealRejectionCode code,
        RealRejectionDetail detail,
        Map<String, Long> figures,
        Optional<String> detailMint,
        boolean pauseForDailyLoss,
        Optional<DaySnapshot> snapshotToRecord) {

    public RealVerdict {
        figures = Collections.unmodifiableMap(new LinkedHashMap<>(figures));
        if (approved == (code != null) || (code == null) != (detail == null)) {
            throw new IllegalArgumentException("a rejection has a code and a detail, an approval has neither");
        }
    }

    static RealVerdict approve(Optional<DaySnapshot> snapshotToRecord) {
        return new RealVerdict(true, null, null, Map.of(), Optional.empty(), false, snapshotToRecord);
    }

    static RealVerdict reject(RealRejectionCode code, RealRejectionDetail detail, Map<String, Long> figures) {
        return new RealVerdict(false, code, detail, figures, Optional.empty(), false, Optional.empty());
    }

    RealVerdict withMint(String mint) {
        return new RealVerdict(approved, code, detail, figures, Optional.of(mint), pauseForDailyLoss, snapshotToRecord);
    }

    RealVerdict withPhoto(Optional<DaySnapshot> photo) {
        return new RealVerdict(approved, code, detail, figures, detailMint, pauseForDailyLoss, photo);
    }

    RealVerdict pausingForDailyLoss() {
        return new RealVerdict(approved, code, detail, figures, detailMint, true, snapshotToRecord);
    }
}
