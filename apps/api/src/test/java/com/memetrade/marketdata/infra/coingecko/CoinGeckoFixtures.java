package com.memetrade.marketdata.infra.coingecko;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * Synthetic CoinGecko answers of the U2 tests (unit-test-instructions.md "Dados"): every mint is generated here from a
 * fixed number (SHA-256 then Base58), never a real token, and the key is a test value that is not a secret. The shapes
 * follow the on-chain routes of the CoinGecko API as the adapter reads them (an assumption to confirm on devnet).
 */
public final class CoinGeckoFixtures {

    /** Not a secret: a fixed test value, only to check that it never reaches a log. */
    public static final String TEST_KEY = "u2-test-key-not-a-secret";

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final ObjectMapper JSON = new ObjectMapper();

    private CoinGeckoFixtures() {}

    /** A synthetic Base58 mint of 32 bytes, the same for the same number. */
    public static String mint(int number) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(("u2-synthetic-mint-" + number).getBytes(StandardCharsets.UTF_8));
            return base58(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String base58(byte[] bytes) {
        BigInteger value = new BigInteger(1, bytes);
        StringBuilder out = new StringBuilder();
        BigInteger base = BigInteger.valueOf(58);
        while (value.signum() > 0) {
            BigInteger[] division = value.divideAndRemainder(base);
            out.append(ALPHABET.charAt(division[1].intValue()));
            value = division[0];
        }
        for (byte b : bytes) {
            if (b != 0) {
                break;
            }
            out.append('1');
        }
        return out.reverse().toString();
    }

    /** Prices of every mint: price 1.5, reserve 25000.25 and volume 1000 (text, as the provider sends them). */
    public static String prices(List<String> mints) {
        ObjectNode prices = JSON.createObjectNode();
        ObjectNode volume = JSON.createObjectNode();
        ObjectNode reserve = JSON.createObjectNode();
        for (String mint : mints) {
            prices.put(mint, "1.5");
            volume.put(mint, "1000");
            reserve.put(mint, "25000.25");
        }
        return pricesWith(prices, volume, reserve);
    }

    /** Prices with explicit values per mint; a missing mint is left out of the three maps. */
    public static String prices(Map<String, String> price, Map<String, String> volume, Map<String, String> reserve) {
        ObjectNode p = JSON.createObjectNode();
        price.forEach(p::put);
        ObjectNode v = JSON.createObjectNode();
        volume.forEach(v::put);
        ObjectNode r = JSON.createObjectNode();
        reserve.forEach(r::put);
        return pricesWith(p, v, r);
    }

    private static String pricesWith(ObjectNode prices, ObjectNode volume, ObjectNode reserve) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode data = root.putObject("data");
        data.put("id", "synthetic");
        data.put("type", "simple_token_price");
        ObjectNode attributes = data.putObject("attributes");
        attributes.set("token_prices", prices);
        attributes.set("h24_volume_usd", volume);
        attributes.set("total_reserve_in_usd", reserve);
        return root.toString();
    }

    /** A pools answer (new or trending) whose base tokens are {@code mints}, with symbol, name and 6 decimals. */
    public static String pools(List<String> mints) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode data = root.putArray("data");
        ArrayNode included = root.putArray("included");
        int index = 0;
        for (String mint : mints) {
            index++;
            ObjectNode pool = data.addObject();
            pool.put("id", "solana_pool" + index);
            pool.put("type", "pool");
            pool.putObject("attributes").put("name", "TK" + index + " / SOL");
            pool.putObject("relationships")
                    .putObject("base_token")
                    .putObject("data")
                    .put("id", "solana_" + mint)
                    .put("type", "token");
            ObjectNode token = included.addObject();
            token.put("id", "solana_" + mint);
            token.put("type", "token");
            token.putObject("attributes")
                    .put("address", mint)
                    .put("name", "Synthetic " + index)
                    .put("symbol", "TK" + index)
                    .put("decimals", 6);
        }
        return root.toString();
    }

    /** A token information answer with the holder count (text, as the provider may send it). */
    public static String info(String mint, String holders) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode attributes = root.putObject("data").putObject("attributes");
        attributes
                .put("address", mint)
                .put("name", "Synthetic")
                .put("symbol", "SYN")
                .put("decimals", 6);
        attributes.putObject("holders").put("count", holders);
        return root.toString();
    }

    /** The top pools of a token: the first one carries the creation time. */
    public static String tokenPools(String createdAt) {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("data").addObject().putObject("attributes").put("pool_created_at", createdAt);
        return root.toString();
    }

    /** The usage of the key as CoinGecko reports it. */
    public static String keyUsage(long monthlyCalls) {
        ObjectNode root = JSON.createObjectNode();
        root.put("plan", "Synthetic");
        root.put("monthly_call_credit", 500_000);
        root.put("current_total_monthly_calls", monthlyCalls);
        return root.toString();
    }
}
