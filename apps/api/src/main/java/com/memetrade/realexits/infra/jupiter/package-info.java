/**
 * The only package of {@code realexits} allowed to reach the network (U7-NFR1.8): the adapter of the Jupiter Trigger
 * API v2 over the existing {@code RestClient}. Empty in this delivery ON PURPOSE (BR0, the gate of the real recording):
 * the adapter and the {@code TriggerPolicy} of the TxSigner are written only after {@code infra/solana-fixtures/record.sh
 * --trigger} recorded a real creation and cancellation, with the attack tests written before the code.
 */
package com.memetrade.realexits.infra.jupiter;
