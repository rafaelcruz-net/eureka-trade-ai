package com.memetrade.txsigner.domain;

import com.memetrade.txsigner.policy.OperationMode;

/** Limits of a policy after a change, as answered to the api (never the key reference). */
public record PolicyView(String botWalletAddress, long capLamports, OperationMode mode, boolean closed) {}
