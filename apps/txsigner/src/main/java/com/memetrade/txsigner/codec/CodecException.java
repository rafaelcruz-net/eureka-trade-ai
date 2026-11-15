package com.memetrade.txsigner.codec;

/**
 * Refused encoded input (Base58, message or transaction). The message never echoes the input itself. Any codec
 * exception turns into a refusal, never into a signature (security-design.md "Falha fechada").
 */
public class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }
}
