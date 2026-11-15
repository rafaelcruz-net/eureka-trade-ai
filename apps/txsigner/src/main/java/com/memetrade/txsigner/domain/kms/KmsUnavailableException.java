package com.memetrade.txsigner.domain.kms;

/** Technical failure of the KMS (timeout, connection, throttling, 5xx): the TxSigner answers 503 and never signs. */
public class KmsUnavailableException extends RuntimeException {

    public KmsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public KmsUnavailableException(String message) {
        super(message);
    }
}
