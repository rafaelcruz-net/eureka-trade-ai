package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * REAL preferences of a bot wallet (entities.md RealPreferences, BR2.1 to BR2.3): lowering is immediate, raising needs
 * a consent the TxSigner checks again, the cap never passes the maximum (10 SOL), and pauses carry their reason.
 */
public record Preferences(
        OperationMode operationMode,
        long capLamports,
        long maxCapLamports,
        int maxSlippageBps,
        long feeReserveLamports,
        long maxWithdrawalFeeLamports,
        Set<PauseReason> pausedReasons,
        Instant updatedAt) {

    public static final int DEFAULT_SLIPPAGE_BPS = 200;

    public Preferences {
        pausedReasons = pausedReasons.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(pausedReasons));
    }

    /** BR1.7: a new wallet starts in SIGNED with the cap of the activation consent. */
    public static Preferences initial(
            long capLamports,
            long feeReserveLamports,
            long maxWithdrawalFeeLamports,
            long maxCapLamports,
            Instant now) {
        return new Preferences(
                OperationMode.SIGNED,
                capLamports,
                maxCapLamports,
                DEFAULT_SLIPPAGE_BPS,
                feeReserveLamports,
                maxWithdrawalFeeLamports,
                Set.of(),
                now);
    }

    public boolean isPaused() {
        return !pausedReasons.isEmpty();
    }

    public Preferences pause(PauseReason reason, Instant now) {
        EnumSet<PauseReason> reasons = reasons();
        reasons.add(reason);
        return with(operationMode, capLamports, reasons, now);
    }

    /** BR2.3: the person removes only the USER reason; another pause is removed by whoever caused it. */
    public Preferences resumeByUser(Instant now) {
        if (!pausedReasons.contains(PauseReason.USER)) {
            throw new BotWalletException(isPaused() ? BotWalletError.REAL_PAUSED : BotWalletError.REAL_NOT_PAUSED);
        }
        EnumSet<PauseReason> reasons = reasons();
        reasons.remove(PauseReason.USER);
        return with(operationMode, capLamports, reasons, now);
    }

    /** BR2.2: lowering the cap is immediate; a higher value needs a consent and is refused here. */
    public Preferences lowerCap(long newCap, Instant now) {
        if (newCap < 0) {
            throw new BotWalletException(BotWalletError.VALIDATION_FAILED);
        }
        if (newCap > capLamports) {
            throw new BotWalletException(BotWalletError.CONSENT_INVALID);
        }
        return with(operationMode, newCap, reasons(), now);
    }

    /** Applied only after the TxSigner accepted the RAISE_CAP consent (BR2.2). */
    public Preferences raiseCap(long newCap, Instant now) {
        if (newCap > maxCapLamports) {
            throw new BotWalletException(BotWalletError.CAP_ABOVE_MAXIMUM);
        }
        return with(operationMode, newCap, reasons(), now);
    }

    /** 261113-trading-real-bot U4 (BR7.1): lowering the mode is immediate and never needs a consent. */
    public Preferences lowerMode(OperationMode mode, Instant now) {
        if (mode.isMorePermissiveThan(operationMode)) {
            throw new BotWalletException(BotWalletError.CONSENT_INVALID);
        }
        return with(mode, capLamports, reasons(), now);
    }

    /** 261113-trading-real-bot U4 (BR7.2): applied only after the TxSigner accepted the RAISE_MODE consent. */
    public Preferences raiseMode(OperationMode mode, Instant now) {
        return with(mode, capLamports, reasons(), now);
    }

    public Preferences withSlippage(int bps, Instant now) {
        if (bps < 0 || bps > 500) {
            throw new BotWalletException(BotWalletError.VALIDATION_FAILED);
        }
        return new Preferences(
                operationMode,
                capLamports,
                maxCapLamports,
                bps,
                feeReserveLamports,
                maxWithdrawalFeeLamports,
                pausedReasons,
                now);
    }

    private Preferences with(OperationMode mode, long cap, Set<PauseReason> reasons, Instant now) {
        return new Preferences(
                mode, cap, maxCapLamports, maxSlippageBps, feeReserveLamports, maxWithdrawalFeeLamports, reasons, now);
    }

    private EnumSet<PauseReason> reasons() {
        return pausedReasons.isEmpty() ? EnumSet.noneOf(PauseReason.class) : EnumSet.copyOf(pausedReasons);
    }
}
