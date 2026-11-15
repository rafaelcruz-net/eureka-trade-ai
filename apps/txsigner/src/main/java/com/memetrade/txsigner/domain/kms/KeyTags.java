package com.memetrade.txsigner.domain.kms;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The four tags written in the creation of a bot wallet key (infrastructure-specification.md "Política do KMS"):
 * {@code app = eureka-botwallet}, {@code mainWalletMac} (HMAC of the main wallet address, never the address itself),
 * {@code creationRequestId} (the challengeId of the activation) and {@code network}. None may be missing.
 */
public record KeyTags(String mainWalletMac, String creationRequestId, String network) {

    public static final String APP = "app";
    public static final String APP_VALUE = "eureka-botwallet";
    public static final String MAIN_WALLET_MAC = "mainWalletMac";
    public static final String CREATION_REQUEST_ID = "creationRequestId";
    public static final String NETWORK = "network";

    public KeyTags {
        requireText(mainWalletMac, MAIN_WALLET_MAC);
        requireText(creationRequestId, CREATION_REQUEST_ID);
        requireText(network, NETWORK);
    }

    /** The tags in the order they are sent to the KMS. */
    public Map<String, String> asMap() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(APP, APP_VALUE);
        tags.put(MAIN_WALLET_MAC, mainWalletMac);
        tags.put(CREATION_REQUEST_ID, creationRequestId);
        tags.put(NETWORK, network);
        return tags;
    }

    /** Reads the tags of an existing key; empty when the key is not a bot wallet key or misses a tag. */
    public static java.util.Optional<KeyTags> from(Map<String, String> tags) {
        Objects.requireNonNull(tags, "tags");
        if (!APP_VALUE.equals(tags.get(APP))) {
            return java.util.Optional.empty();
        }
        String mac = tags.get(MAIN_WALLET_MAC);
        String request = tags.get(CREATION_REQUEST_ID);
        String network = tags.get(NETWORK);
        if (isBlank(mac) || isBlank(request) || isBlank(network)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new KeyTags(mac, request, network));
    }

    private static void requireText(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("Key tag " + name + " is required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
