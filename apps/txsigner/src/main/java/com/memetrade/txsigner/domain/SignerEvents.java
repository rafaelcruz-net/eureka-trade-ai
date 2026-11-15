package com.memetrade.txsigner.domain;

/** Outlet of the audit events and metrics of the TxSigner; the infrastructure writes the log line and the counters. */
public interface SignerEvents {

    void publish(SignerEvent event);
}
