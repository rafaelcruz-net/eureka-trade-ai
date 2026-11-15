import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * kms-sim: TEST-ONLY simulator of the AWS KMS JSON protocol for the local Compose (infrastructure-specification.md,
 * contract X4 of the addendum, NFR1.6). It is never part of a production artifact: the production profile of the
 * TxSigner refuses any KMS endpoint other than AWS. Keys live in memory only and are lost on restart.
 *
 * <p>Supported operations ({@code X-Amz-Target: TrentService.<Operation>}): CreateKey (exactly the four tags app,
 * mainWalletMac, creationRequestId and network, like the IAM conditions), GetPublicKey, Sign (pure Ed25519 over RAW),
 * ListKeys, DescribeKey, ListResourceTags, TagResource and UntagResource (always AccessDenied: tags are immutable),
 * GenerateMac and VerifyMac (one HMAC key, id {@code hmac-anchor-key}). Run with {@code java KmsSim.java}.
 */
public final class KmsSim {

    private static final Set<String> TAGS = Set.of("app", "mainWalletMac", "creationRequestId", "network");
    private static final Map<String, Key> KEYS = new ConcurrentHashMap<>();
    private static final byte[] HMAC_KEY = new byte[32];
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    /**
     * 261113-trading-real-bot U4 (infrastructure-specification.md "Chave determinística no KMS simulado"): with
     * {@code KMS_SIM_SEED} the key of the n-th CreateKey is derived from SHA-256(seed + ":" + n), so the bot wallet of the
     * test user of the REAL Compose always has the same address and the recorded swap matches it. Accepted only with
     * {@code KMS_SIM_PROFILE} test or local; without the seed the keys stay random, as before.
     */
    private static final String SEED = System.getenv().getOrDefault("KMS_SIM_SEED", "");

    private static final java.util.concurrent.atomic.AtomicLong CREATED = new java.util.concurrent.atomic.AtomicLong();

    private record Key(KeyPair pair, Map<String, String> tags, Instant createdAt) {}

    private static final class Refused extends RuntimeException {
        final String type;

        Refused(String type, String message) {
            super(message);
            this.type = type;
        }
    }

    public static void main(String[] args) throws IOException {
        String profile = System.getenv().getOrDefault("KMS_SIM_PROFILE", "");
        if (!SEED.isEmpty() && !Set.of("test", "local").contains(profile)) {
            System.err.println("kms-sim: KMS_SIM_SEED is accepted only with KMS_SIM_PROFILE test or local");
            System.exit(2);
        }
        new SecureRandom().nextBytes(HMAC_KEY);
        int port = Integer.parseInt(System.getenv().getOrDefault("KMS_SIM_PORT", "4599"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", KmsSim::handle);
        server.createContext("/health", exchange -> reply(exchange, 200, "{\"status\":\"UP\"}"));
        server.start();
        System.out.println("kms-sim listening on " + port + " (TEST ONLY)");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String operation = target == null ? "" : target.substring(target.indexOf('.') + 1);
        try {
            reply(exchange, 200, answer(operation, body));
        } catch (Refused refused) {
            exchange.getResponseHeaders().add("x-amzn-ErrorType", refused.type);
            reply(exchange, 400, "{\"__type\":\"" + refused.type + "\",\"message\":\"" + refused.getMessage() + "\"}");
        } catch (GeneralSecurityException failure) {
            reply(exchange, 500, "{\"__type\":\"KMSInternalException\",\"message\":\"internal\"}");
        }
    }

    private static String answer(String operation, String body) throws GeneralSecurityException {
        String keyId = field(body, "KeyId");
        return switch (operation) {
            case "CreateKey" -> createKey(body);
            case "GetPublicKey" -> "{\"KeyId\":\"" + keyId + "\",\"KeySpec\":\"ECC_NIST_EDWARDS25519\",\"PublicKey\":\""
                    + B64.encodeToString(key(keyId).pair().getPublic().getEncoded()) + "\"}";
            case "Sign" -> sign(keyId, body);
            case "ListKeys" -> "{\"Truncated\":false,\"Keys\":[" + String.join(",", KEYS.keySet().stream()
                    .map(id -> "{\"KeyId\":\"" + id + "\"}").toList()) + "]}";
            case "DescribeKey" -> "{\"KeyMetadata\":" + metadata(keyId) + "}";
            case "ListResourceTags" -> "{\"Truncated\":false,\"Tags\":[" + String.join(",", key(keyId).tags()
                    .entrySet().stream()
                    .map(e -> "{\"TagKey\":\"" + e.getKey() + "\",\"TagValue\":\"" + e.getValue() + "\"}")
                    .toList()) + "]}";
            case "TagResource", "UntagResource" -> throw new Refused(
                    "AccessDeniedException", "tags are immutable after creation");
            case "GenerateMac" -> "{\"KeyId\":\"hmac-anchor-key\",\"MacAlgorithm\":\"HMAC_SHA_256\",\"Mac\":\""
                    + B64.encodeToString(mac(B64D.decode(field(body, "Message")))) + "\"}";
            case "VerifyMac" -> verifyMac(body);
            default -> throw new Refused("UnsupportedOperationException", "unsupported " + operation);
        };
    }

    private static String createKey(String body) throws GeneralSecurityException {
        Map<String, String> tags = new LinkedHashMap<>();
        Matcher tag = Pattern.compile("\\{\\s*\"TagKey\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"TagValue\"\\s*:\\s*\"([^\"]*)\"\\s*}")
                .matcher(body);
        while (tag.find()) {
            tags.put(tag.group(1), tag.group(2));
        }
        if (!tags.keySet().equals(TAGS) || !"eureka-botwallet".equals(tags.get("app"))) {
            throw new Refused("AccessDeniedException", "CreateKey requires exactly the four tags");
        }
        String keyId = UUID.randomUUID().toString();
        KEYS.put(keyId, new Key(newPair(), tags, Instant.now()));
        return "{\"KeyMetadata\":" + metadata(keyId) + "}";
    }

    /** A random pair, or with the seed the pair of the n-th creation (deterministic, test only). */
    private static KeyPair newPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        if (SEED.isEmpty()) {
            return generator.generateKeyPair();
        }
        byte[] material = MessageDigest.getInstance("SHA-256")
                .digest((SEED + ":" + CREATED.getAndIncrement()).getBytes(StandardCharsets.UTF_8));
        SecureRandom fixed = SecureRandom.getInstance("SHA1PRNG");
        fixed.setSeed(material);
        generator.initialize(255, fixed);
        return generator.generateKeyPair();
    }

    private static String sign(String keyId, String body) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key(keyId).pair().getPrivate());
        signer.update(B64D.decode(field(body, "Message")));
        return "{\"KeyId\":\"" + keyId + "\",\"SigningAlgorithm\":\"ED25519_SHA_512\",\"Signature\":\""
                + B64.encodeToString(signer.sign()) + "\"}";
    }

    private static String verifyMac(String body) throws GeneralSecurityException {
        byte[] expected = mac(B64D.decode(field(body, "Message")));
        if (!MessageDigest.isEqual(expected, B64D.decode(field(body, "Mac")))) {
            throw new Refused("KMSInvalidMacException", "MAC is not valid");
        }
        return "{\"KeyId\":\"hmac-anchor-key\",\"MacValid\":true,\"MacAlgorithm\":\"HMAC_SHA_256\"}";
    }

    private static byte[] mac(byte[] message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_KEY, "HmacSHA256"));
        return mac.doFinal(message);
    }

    private static String metadata(String keyId) {
        Key key = key(keyId);
        return "{\"KeyId\":\"" + keyId + "\",\"Arn\":\"arn:aws:kms:us-east-1:000000000000:key/" + keyId
                + "\",\"KeyManager\":\"CUSTOMER\",\"KeySpec\":\"ECC_NIST_EDWARDS25519\",\"KeyUsage\":\"SIGN_VERIFY\","
                + "\"KeyState\":\"Enabled\",\"CreationDate\":" + key.createdAt().getEpochSecond() + "}";
    }

    private static Key key(String keyId) {
        Key key = KEYS.get(keyId);
        if (key == null) {
            throw new Refused("NotFoundException", "unknown key");
        }
        return key;
    }

    private static String field(String body, String name) {
        Matcher value = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return value.find() ? value.group(1) : "";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.1");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
