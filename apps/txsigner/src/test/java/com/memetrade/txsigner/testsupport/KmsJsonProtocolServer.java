package com.memetrade.txsigner.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.txsigner.domain.kms.KmsRefusedException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loopback server of the AWS KMS JSON protocol ({@code X-Amz-Target: TrentService.<Operation>}) over the in-memory
 * {@link KmsSimulator}, so the REAL AWS SDK client and the {@code AwsKmsAdapter} are exercised in the tests without an
 * AWS account (NFR8.4). Same protocol as the {@code kms-sim} container of infra/kms-sim. Test code only.
 */
public final class KmsJsonProtocolServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final HttpServer server;
    private final KmsSimulator kms;
    private final String hmacKeyId = "hmac-anchor-key";

    public KmsJsonProtocolServer(KmsSimulator kms) throws IOException {
        this.kms = kms;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public String hmacKeyId() {
        return hmacKeyId;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
        String operation = target == null ? "" : target.substring(target.indexOf('.') + 1);
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        try {
            reply(exchange, 200, answer(operation, request));
        } catch (KmsRefusedException refused) {
            String type = refused.getMessage().startsWith("NotFound") ? "NotFoundException" : "AccessDeniedException";
            reply(exchange, 400, error(type, refused.getMessage()));
        } catch (InvalidMac invalid) {
            reply(exchange, 400, error("KMSInvalidMacException", "MAC is not valid"));
        }
    }

    private ObjectNode answer(String operation, JsonNode request) {
        ObjectNode out = JSON.createObjectNode();
        String keyId = request.path("KeyId").asText();
        switch (operation) {
            case "CreateKey" -> {
                Map<String, String> tags = new LinkedHashMap<>();
                request.path("Tags")
                        .forEach(t -> tags.put(
                                t.path("TagKey").asText(), t.path("TagValue").asText()));
                metadata(out.putObject("KeyMetadata"), kms.createKeyWithTags(tags));
            }
            case "GetPublicKey" -> {
                out.put("KeyId", keyId);
                out.put("PublicKey", B64.encodeToString(kms.publicKeyDer(keyId)));
                out.put("KeySpec", "ECC_NIST_EDWARDS25519");
            }
            case "Sign" -> {
                out.put("KeyId", keyId);
                out.put(
                        "Signature",
                        B64.encodeToString(kms.sign(
                                keyId, B64D.decode(request.path("Message").asText()))));
                out.put("SigningAlgorithm", request.path("SigningAlgorithm").asText());
            }
            case "ListKeys" -> {
                ArrayNode keys = out.putArray("Keys");
                kms.listKeyRefs().forEach(ref -> keys.addObject().put("KeyId", ref));
                keys.addObject().put("KeyId", "aws-managed-key");
                out.put("Truncated", false);
            }
            case "DescribeKey" -> {
                if ("aws-managed-key".equals(keyId)) {
                    throw new KmsRefusedException("AccessDeniedException: AWS managed key");
                }
                metadata(out.putObject("KeyMetadata"), keyId);
            }
            case "ListResourceTags" -> {
                ArrayNode tags = out.putArray("Tags");
                kms.tags(keyId)
                        .forEach((k, v) -> tags.addObject().put("TagKey", k).put("TagValue", v));
                out.put("Truncated", false);
            }
            case "TagResource" -> kms.tagResource(keyId, Map.of());
            case "GenerateMac" ->
                out.put(
                        "Mac",
                        B64.encodeToString(
                                kms.mac(B64D.decode(request.path("Message").asText()))));
            case "VerifyMac" -> {
                boolean valid = kms.verifyMac(
                        B64D.decode(request.path("Message").asText()),
                        B64D.decode(request.path("Mac").asText()));
                if (!valid) {
                    throw new InvalidMac();
                }
                out.put("MacValid", true);
            }
            default -> throw new KmsRefusedException("AccessDeniedException: unsupported " + operation);
        }
        return out;
    }

    private void metadata(ObjectNode metadata, String keyId) {
        metadata.put("KeyId", keyId);
        metadata.put("Arn", "arn:aws:kms:us-east-1:000000000000:key/" + keyId);
        metadata.put("KeyManager", "CUSTOMER");
        metadata.put("KeySpec", "ECC_NIST_EDWARDS25519");
        metadata.put("KeyUsage", "SIGN_VERIFY");
        metadata.put("CreationDate", kms.keyCreatedAt(keyId).getEpochSecond());
    }

    private static ObjectNode error(String type, String message) {
        ObjectNode error = JSON.createObjectNode();
        error.put("__type", type);
        error.put("message", message);
        return error;
    }

    private static void reply(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.1");
        if (status != 200) {
            exchange.getResponseHeaders()
                    .add("x-amzn-ErrorType", body.path("__type").asText());
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static final class InvalidMac extends RuntimeException {}

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
