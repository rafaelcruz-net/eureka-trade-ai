package com.memetrade.txsigner.domain.kms;

import java.util.List;
import java.util.Map;

/**
 * Port of the key management service (contract X4). Implemented by the AWS KMS adapter in production and by the
 * {@code kms-sim} container in the local Compose; the unit tests use an in-memory double with the same contract. The
 * port has no operation that returns, imports or exports private key material, and none that changes a tag after the
 * creation: the tags are the immutable anchor of the wallet (security-design.md).
 */
public interface KmsPort {

    /** Creates one non-exportable Ed25519 key with its four tags given in the creation itself; returns its reference. */
    String createKey(KeyTags tags);

    /** Raw 32-byte Ed25519 public key of the key (the Solana address is its Base58). */
    byte[] publicKey(String keyRef);

    /** Pure Ed25519 (RFC 8032) signature, 64 bytes, over the exact message bytes. */
    byte[] sign(String keyRef, byte[] message);

    /** References of the customer-managed keys of the account (AWS-managed keys are skipped). */
    List<String> listKeyRefs();

    /** Tags of a key as stored at creation. */
    Map<String, String> tags(String keyRef);

    /** Creation instant of a key (DescribeKey), for the age of an orphan key. */
    java.time.Instant keyCreatedAt(String keyRef);

    /** HMAC-SHA-256 with the anchor key (GenerateMac); used for the tag {@code mainWalletMac}. */
    byte[] mac(byte[] data);

    /** VerifyMac with the anchor key; {@code false} when the MAC does not match. */
    boolean verifyMac(byte[] data, byte[] mac);

    /** Readiness probe (ListKeys): {@code true} when the KMS answers. */
    boolean isReachable();
}
