package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealConsentRefused;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.identity.api.Principal;
import com.memetrade.realexecution.api.CancelScope;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F7 ({@code PUT /api/v1/real/mode}). BR7.1: lowering is immediate (M1 lowerMode) and, in the SAME transaction, cancels
 * the PROPOSED and QUOTED orders of the person with MODE_LOWERED and their reservation released; SIGNED orders are
 * cancelled by the recheck before the send; SENT and AMBIGUOUS ones are followed to the end. BR7.2: raising needs the
 * consent of the main wallet (M1 applyConsent, checked again by the TxSigner) and is refused with an AMBIGUOUS order.
 */
@Service
public class RealModeService {

    private final RealExecutionWallet wallets;
    private final RealExecutionPort port;
    private final JdbcRealExecutionStore store;
    private final Environment environment;
    private final TransactionTemplate transactions;

    public RealModeService(
            RealExecutionWallet wallets,
            RealExecutionPort port,
            JdbcRealExecutionStore store,
            Environment environment,
            PlatformTransactionManager manager) {
        this.wallets = wallets;
        this.port = port;
        this.store = store;
        this.environment = environment;
        this.transactions = new TransactionTemplate(manager);
    }

    /** @return the mode in force after the change */
    public String change(Principal person, String mode, UUID challengeId, String signature) {
        RealOperationMode wanted;
        try {
            wanted = RealOperationMode.valueOf(mode == null ? "" : mode);
        } catch (IllegalArgumentException unknown) {
            throw ApiException.validation("mode", "Informe SIGNED, AUTO_EXITS ou AUTO_FULL.");
        }
        RealWalletInfo wallet = wallets.wallet(person.tenantId(), person.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.BOT_WALLET_NOT_ACTIVE, "No active bot wallet."));
        if (wanted == wallet.mode()) {
            return wanted.name();
        }
        if (wanted.ordinal() < wallet.mode().ordinal()) {
            transactions.executeWithoutResult(status -> {
                wallets.lowerMode(person.tenantId(), person.userId(), wanted);
                port.cancelUnsent(CancelScope.user(person.tenantId(), person.userId()), RealFailureCode.MODE_LOWERED);
            });
            return wanted.name();
        }
        if (store.hasAmbiguous(person.tenantId(), person.userId())) {
            throw new ApiException(ErrorCode.ORDER_AMBIGUOUS, "An ambiguous order is still being checked.");
        }
        if (!environment.getProperty("memetrade.real.enabled", Boolean.class, false)) {
            throw new ApiException(ErrorCode.REAL_DISABLED, "REAL is disabled in this environment.");
        }
        try {
            return wallets.applyModeConsent(person, wanted, challengeId, signature)
                    .name();
        } catch (RealConsentRefused refused) {
            throw new ApiException(errorOf(refused.code()), "The consent was refused.");
        } catch (RealUpstreamUnavailable down) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "The signing service did not answer.");
        }
    }

    private static ErrorCode errorOf(String code) {
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException unknown) {
            return ErrorCode.CONSENT_INVALID;
        }
    }
}
