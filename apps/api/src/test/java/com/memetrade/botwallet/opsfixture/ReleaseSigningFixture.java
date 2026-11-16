package com.memetrade.botwallet.opsfixture;

import com.memetrade.botwallet.domain.TxSignerPort;
import java.util.UUID;

/** TEST-ONLY: a U5 operation class asking a withdrawal signature, which RealOpsArchitectureTest must refuse. */
public final class ReleaseSigningFixture {

    public TxSignerPort.SignOutcome sign(TxSignerPort signer) {
        return signer.signWithdrawal(UUID.randomUUID(), "address", "AQID", 1L, 1L);
    }
}
