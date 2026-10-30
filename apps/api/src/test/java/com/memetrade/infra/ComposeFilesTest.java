package com.memetrade.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.infra.SmokeAccountSeeder;
import com.memetrade.testsupport.AbstractPostgresIT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.yaml.snakeyaml.Yaml;

/** Reads the versioned Compose and Dockerfiles (CI gate of cicd-pipeline.md, infrastructure-specification.md). */
@Tag("u1")
class ComposeFilesTest {

    private static final Path ROOT = Path.of("..", "..");

    @Test
    void theDefaultComposeFileNeverEnablesTheTestProfileTheSeedOrARaisedLimit() throws IOException {
        String compose = read("docker-compose.yml");

        assertThat(compose)
                .doesNotContain("SPRING_PROFILES_ACTIVE")
                .doesNotContain("SMOKE_BETA_WALLET_ADDRESS")
                .doesNotContain("LOGIN_RATE_LIMIT_PER_MINUTE");
    }

    @Test
    void theTestOverlayIsTheOnlyPlaceThatActivatesTheTestProfile() throws IOException {
        Map<String, Object> overlay = yaml("infra/docker-compose.test.yml");

        assertThat(String.valueOf(overlay)).contains("SPRING_PROFILES_ACTIVE").contains("SMOKE_BETA_WALLET_ADDRESS");
        assertThat(read("infra/docker-compose.test.yml")).contains("${SPRING_PROFILES_ACTIVE:-test}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void portsAreBoundToLoopbackAndServicesHaveHealthChecksLimitsAndRotatedLogs() throws IOException {
        Map<String, Object> services =
                (Map<String, Object>) yaml("docker-compose.yml").get("services");

        assertThat(services).containsOnlyKeys("postgres", "db-bootstrap", "db-migrate", "api", "web");
        for (String oneShot : List.of("db-bootstrap", "db-migrate")) {
            Map<String, Object> service = (Map<String, Object>) services.get(oneShot);
            assertThat(service).as(oneShot).doesNotContainKey("ports").containsEntry("restart", "no");
            assertThat(String.valueOf(((Map<String, Object>) service.get("logging")).get("options")))
                    .contains("max-size=10m")
                    .contains("max-file=3");
        }
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            if (List.of("db-bootstrap", "db-migrate").contains(entry.getKey())) {
                continue;
            }
            Map<String, Object> service = (Map<String, Object>) entry.getValue();
            assertThat((List<String>) service.get("ports"))
                    .as(entry.getKey())
                    .allMatch(port -> port.startsWith("127.0.0.1:"));
            assertThat(service).as(entry.getKey()).containsKeys("healthcheck", "mem_limit", "logging");
            Map<String, Object> logging = (Map<String, Object>) service.get("logging");
            assertThat(String.valueOf(logging.get("options")))
                    .contains("max-size=10m")
                    .contains("max-file=3");
        }
    }

    /** NFR6.3: only db-migrate gets the owner credential; the superuser stays with postgres and db-bootstrap. */
    @Test
    @SuppressWarnings("unchecked")
    void databaseCredentialsReachOnlyTheirServicesAndTheStartOrderHolds() throws IOException {
        Map<String, Object> services =
                (Map<String, Object>) yaml("docker-compose.yml").get("services");
        Map<String, Object> api = (Map<String, Object>) services.get("api");
        Map<String, Object> migrate = (Map<String, Object>) services.get("db-migrate");
        Map<String, Object> postgres = (Map<String, Object>) services.get("postgres");

        assertThat((Map<String, Object>) api.get("environment"))
                .doesNotContainKeys("DB_OWNER_USER", "DB_OWNER_PASSWORD", "POSTGRES_PASSWORD")
                .containsKeys("DB_USER", "DB_PASSWORD", "DB_RETENTION_USER", "DB_RETENTION_PASSWORD");
        assertThat((Map<String, Object>) migrate.get("environment"))
                .doesNotContainKeys("POSTGRES_PASSWORD", "DB_PASSWORD", "DB_RETENTION_PASSWORD")
                .containsKeys("DB_OWNER_USER", "DB_OWNER_PASSWORD");
        assertThat(String.valueOf(api.get("depends_on")))
                .contains("db-migrate")
                .contains("service_completed_successfully");
        assertThat(String.valueOf(migrate.get("depends_on")))
                .contains("db-bootstrap")
                .contains("service_completed_successfully");
        assertThat(String.valueOf(postgres.get("healthcheck"))).contains("pg_isready -h 127.0.0.1");
    }

    @Test
    void everyBaseImageIsPinnedByDigest() throws IOException {
        for (String file : List.of("docker-compose.yml", "apps/api/Dockerfile", "apps/web/Dockerfile")) {
            Matcher images =
                    Pattern.compile("(?m)^\\s*(?:image:|FROM)\\s+(\\S+)").matcher(read(file));
            while (images.find()) {
                String image = images.group(1);
                boolean earlierStage = read(file).contains(" AS " + image + "\n");
                if (image.startsWith("memetrade-") || earlierStage) {
                    continue;
                }
                assertThat(image).as(file).matches(".+@sha256:[0-9a-f]{64}");
            }
        }
    }

    @Test
    void imagesRunAsANonRootUser() throws IOException {
        assertThat(read("apps/api/Dockerfile")).containsPattern("(?m)^USER memetrade$");
        assertThat(read("apps/web/Dockerfile")).containsPattern("(?m)^USER memetrade$");
    }

    @Test
    void testcontainersUsesTheSamePostgresMajorAsCompose() throws IOException {
        assertThat(read("docker-compose.yml")).contains("image: " + AbstractPostgresIT.POSTGRES_IMAGE + "@sha256:");
    }

    @Test
    void theSmokeSeedExistsOnlyInTheTestProfile() {
        Profile profile = SmokeAccountSeeder.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("test");
    }

    // ----- U2 (NFR6.3, NFR6.5, NFR4.2, NFR7.1): the same rules hold for the base file and for base + test overlay. ---

    private static final List<String> SUPERUSER_AND_OWNER = List.of("POSTGRES_PASSWORD", "DB_OWNER_PASSWORD");

    /** Credentials per service, on the base file and on the merged base + test overlay. */
    @Test
    @Tag("u2")
    void inBothCompositionsEachCredentialReachesOnlyItsServices() throws IOException {
        for (Map<String, Map<String, Object>> services : List.of(services(false), services(true))) {
            assertThat(environment(services, "api"))
                    .doesNotContainKeys("DB_OWNER_USER", "DB_OWNER_PASSWORD", "POSTGRES_USER", "POSTGRES_PASSWORD")
                    .containsKeys("DB_PASSWORD", "DB_RETENTION_PASSWORD", "AUDIT_RETENTION_MONTHS");
            assertThat(environment(services, "db-migrate"))
                    .doesNotContainKeys("POSTGRES_PASSWORD", "DB_PASSWORD", "DB_RETENTION_PASSWORD")
                    .containsKeys("DB_OWNER_USER", "DB_OWNER_PASSWORD");
            assertThat(environment(services, "db-bootstrap"))
                    .containsKeys("POSTGRES_PASSWORD", "DB_OWNER_PASSWORD", "DB_PASSWORD", "DB_RETENTION_PASSWORD");
            assertThat(environment(services, "postgres"))
                    .containsKey("POSTGRES_PASSWORD")
                    .doesNotContainKeys("DB_OWNER_PASSWORD", "DB_PASSWORD", "DB_RETENTION_PASSWORD");
            assertThat(String.valueOf(environment(services, "web")))
                    .doesNotContain("PASSWORD")
                    .doesNotContain("SIGNING_KEY");
        }
    }

    /** The test overlay changes only the api and adds no credential at all. */
    @Test
    @Tag("u2")
    @SuppressWarnings("unchecked")
    void theTestOverlayTouchesOnlyTheApiAndAddsNoCredential() throws IOException {
        Map<String, Object> overlay =
                (Map<String, Object>) yaml("infra/docker-compose.test.yml").get("services");

        assertThat(overlay).containsOnlyKeys("api");
        assertThat(String.valueOf(overlay)).doesNotContain("PASSWORD").doesNotContain("DB_OWNER");
    }

    /** Start order in both compositions: postgres (healthy) -> db-bootstrap -> db-migrate -> api -> web. */
    @Test
    @Tag("u2")
    void inBothCompositionsTheStartOrderHolds() throws IOException {
        for (Map<String, Map<String, Object>> services : List.of(services(false), services(true))) {
            assertThat(dependsOn(services, "db-bootstrap")).containsEntry("postgres", "service_healthy");
            assertThat(dependsOn(services, "db-migrate"))
                    .containsExactly(Map.entry("db-bootstrap", "service_completed_successfully"));
            assertThat(dependsOn(services, "api"))
                    .containsExactly(Map.entry("db-migrate", "service_completed_successfully"));
            assertThat(dependsOn(services, "web")).containsEntry("api", "service_healthy");
        }
    }

    /** Development mode: `docker compose up postgres db-bootstrap db-migrate` starts without the api or web. */
    @Test
    @Tag("u2")
    void theDevelopmentModeStartsDbBootstrapAndDbMigrateWithoutTheApi() throws IOException {
        Map<String, Map<String, Object>> services = services(false);
        java.util.Set<String> needed = new java.util.HashSet<>();
        for (String service : List.of("postgres", "db-bootstrap", "db-migrate")) {
            closure(services, service, needed);
        }

        assertThat(needed).containsExactlyInAnyOrder("postgres", "db-bootstrap", "db-migrate");
        assertThat(read("docker-compose.yml")).contains("docker compose up postgres db-bootstrap db-migrate");
        assertThat(read("README.md")).contains("docker compose up postgres db-bootstrap db-migrate");
    }

    /** NFR4.2 and NFR7.1: TCP healthcheck and the same server parameters as the integration tests. */
    @Test
    @Tag("u2")
    @SuppressWarnings("unchecked")
    void postgresHasTheTcpHealthcheckAndTheServerParametersOfTheTests() throws IOException {
        Map<String, Object> postgres = services(true).get("postgres");
        List<String> command = (List<String>) postgres.get("command");
        List<String> parameters = new java.util.ArrayList<>();
        for (int i = 0; i < command.size() - 1; i++) {
            if ("-c".equals(command.get(i))) {
                parameters.add(command.get(i + 1));
            }
        }

        assertThat(command.getFirst()).isEqualTo("postgres");
        assertThat(parameters).containsExactlyElementsOf(AbstractPostgresIT.SERVER_PARAMETERS);
        assertThat(String.valueOf(postgres.get("healthcheck")))
                .contains("pg_isready -h 127.0.0.1")
                .doesNotContain("/var/run/postgresql");
    }

    /** Every variable the compositions read is listed in .env.example, without a value (team.md). */
    @Test
    @Tag("u2")
    void everyComposeVariableIsListedInTheEnvExampleWithoutAValue() throws IOException {
        String example = read(".env.example");
        java.util.Set<String> testOnly =
                java.util.Set.of("SPRING_PROFILES_ACTIVE", "SMOKE_BETA_WALLET_ADDRESS", "LOGIN_RATE_LIMIT_PER_MINUTE");
        Matcher variables = Pattern.compile("\\$\\{([A-Z0-9_]+)")
                .matcher(read("docker-compose.yml") + read("infra/docker-compose.test.yml"));
        java.util.Set<String> missing = new java.util.TreeSet<>();
        while (variables.find()) {
            String name = variables.group(1);
            boolean listed = example.contains("\n" + name + "=\n") || example.contains("# " + name + "=");
            if (!listed && !testOnly.contains(name)) {
                missing.add(name);
            }
        }

        assertThat(missing).isEmpty();
        assertThat(example).doesNotContainPattern("(?m)^[A-Z0-9_]+=\\S");
        assertThat(example).contains("\nAUDIT_RETENTION_MONTHS=\n").contains("\nDB_OWNER_PASSWORD=\n");
    }

    /** Merged view of the services: the base file, and with {@code overlay} the test overlay merged on top. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services(boolean overlay) throws IOException {
        Map<String, Map<String, Object>> merged = new java.util.LinkedHashMap<>();
        ((Map<String, Object>) yaml("docker-compose.yml").get("services"))
                .forEach((name, value) -> merged.put(name, deepCopy((Map<String, Object>) value)));
        if (overlay) {
            ((Map<String, Object>) yaml("infra/docker-compose.test.yml").get("services"))
                    .forEach((name, value) -> mergeInto(
                            merged.computeIfAbsent(name, k -> new java.util.LinkedHashMap<>()),
                            (Map<String, Object>) value));
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static void mergeInto(Map<String, Object> target, Map<String, Object> source) {
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map && target.get(key) instanceof Map<?, ?>) {
                mergeInto((Map<String, Object>) target.get(key), (Map<String, Object>) map);
            } else {
                target.put(key, value);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        source.forEach((key, value) ->
                copy.put(key, value instanceof Map<?, ?> map ? deepCopy((Map<String, Object>) map) : value));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment(Map<String, Map<String, Object>> services, String name) {
        Object environment = services.get(name).get("environment");
        return environment == null ? Map.of() : (Map<String, Object>) environment;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> dependsOn(Map<String, Map<String, Object>> services, String name) {
        Map<String, String> conditions = new java.util.LinkedHashMap<>();
        Object dependsOn = services.get(name).get("depends_on");
        if (dependsOn instanceof Map<?, ?> map) {
            map.forEach((service, spec) -> conditions.put(
                    String.valueOf(service), String.valueOf(((Map<String, Object>) spec).get("condition"))));
        }
        return conditions;
    }

    private static void closure(Map<String, Map<String, Object>> services, String name, java.util.Set<String> out) {
        if (out.add(name)) {
            dependsOn(services, name).keySet().forEach(dependency -> closure(services, dependency, out));
        }
    }

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static Map<String, Object> yaml(String file) throws IOException {
        return new Yaml().load(read(file));
    }
}
