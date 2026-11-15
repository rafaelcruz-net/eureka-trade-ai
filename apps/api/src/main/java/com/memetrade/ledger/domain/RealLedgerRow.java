package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.RealEntryKind;

/** Sum of the REAL entries of one kind and one reference of a wallet, as read from the ledger. */
public record RealLedgerRow(RealEntryKind kind, long lamports, String reference) {}
