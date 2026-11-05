package com.memetrade.decision.domain;

/** Why a position is sold whole (BR4.5), in this order of priority. */
public enum ExitReason {
    TAKE_PROFIT,
    STOP_LOSS,
    MAX_HOLD,
    /** Only RANGE_BREAKOUT: the last bucket price went below the minimum of the range. */
    RANGE_EXIT
}
