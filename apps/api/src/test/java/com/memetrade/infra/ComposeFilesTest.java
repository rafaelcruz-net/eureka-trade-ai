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

        // U5: the only profile the base file names is the worker's own, on the worker service.
        assertThat(compose.replace("SPRING_PROFILES_ACTIVE: worker\n", ""))
                .doesNotContain("SPRING_PROFILES_ACTIVE")
                .doesNotContain("SMOKE_BETA_WALLET_ADDRESS")
                .doesNotContain("LOGIN_RATE_LIMIT_PER_MINUTE");
        assertThat(compose).doesNotContainPattern("SPRING_PROFILES_ACTIVE: .*test");
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

        // U8: the administrator app is the seventh service.
        assertThat(services)
                .containsOnlyKeys("postgres", "db-bootstrap", "db-migrate", "api", "worker", "web", "admin");
        // U5 (NFR10.9): the worker publishes no port in the base file.
        assertThat((Map<String, Object>) services.get("worker")).doesNotContainKey("ports");
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
            List<String> ports = (List<String>) service.getOrDefault("ports", List.of());
            assertThat(ports).as(entry.getKey()).allMatch(port -> port.startsWith("127.0.0.1:"));
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
        for (String file :
                List.of("docker-compose.yml", "apps/api/Dockerfile", "apps/web/Dockerfile", "apps/admin/Dockerfile")) {
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
        assertThat(read("apps/admin/Dockerfile")).containsPattern("(?m)^USER memetrade$");
    }

    /**
     * U8 (infrastructure-specification.md Q1, R-04, R-07): the administrator app on loopback 3001, after a healthy api,
     * with its health route, 512 MB and only API_ORIGIN and GUEST_APP_ORIGIN; no secret, no credential, no
     * NEXT_PUBLIC_* variable. The test overlay passes the administrator wallet of the smoke to the api only.
     */
    @Test
    @Tag("u8")
    @SuppressWarnings("unchecked")
    void theAdministratorAppIsAServiceOfItsOwnWithoutSecrets() throws IOException {
        for (Map<String, Map<String, Object>> services : List.of(services(false), services(true))) {
            Map<String, Object> admin = services.get("admin");
            assertThat((List<String>) admin.get("ports")).containsExactly("127.0.0.1:3001:3000");
            assertThat(dependsOn(services, "admin")).containsExactly(Map.entry("api", "service_healthy"));
            assertThat(String.valueOf(admin.get("healthcheck"))).contains("http://127.0.0.1:3000/api/health");
            assertThat(String.valueOf(admin.get("mem_limit"))).isEqualTo("512m");
            // u1-legal-documents: plus the server-only origin used to read the legal documents inside the network.
            assertThat(environment(services, "admin"))
                    .containsOnlyKeys("API_ORIGIN", "GUEST_APP_ORIGIN", "API_INTERNAL_ORIGIN")
                    .containsEntry("API_ORIGIN", "${API_ORIGIN:-http://localhost:8080}");
            assertThat(String.valueOf(admin))
                    .doesNotContain("PASSWORD")
                    .doesNotContain("SIGNING_KEY")
                    .doesNotContain("NEXT_PUBLIC");
            assertThat(String.valueOf(environment(services, "api").get("ADMIN_APP_ORIGIN")))
                    .isEqualTo("${ADMIN_APP_ORIGIN:-http://localhost:3001}");
            assertThat(String.valueOf(environment(services, "api").get("SCHEDULER_POOL_SIZE")))
                    .isEqualTo("${SCHEDULER_POOL_SIZE:-16}");
        }
        assertThat(String.valueOf(environment(services(true), "api").get("ADMIN_WALLET_ADDRESSES")))
                .isEqualTo("${ADMIN_WALLET_ADDRESSES:-}");
        assertThat(read("apps/admin/Dockerfile"))
                .contains("pnpm install --frozen-lockfile")
                .contains("NEXT_TELEMETRY_DISABLED=1")
                .contains("/api/health");
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

        // U5: the overlay also gives the worker the test profile and the loopback actuator port of the load scenario.
        assertThat(overlay).containsOnlyKeys("api", "worker");
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
        java.util.Set<String> testOnly = java.util.Set.of(
                "SPRING_PROFILES_ACTIVE",
                "SMOKE_BETA_WALLET_ADDRESS",
                "LOGIN_RATE_LIMIT_PER_MINUTE",
                // U3: test-profile seeds and the loadtest-only lookup limit; never production variables.
                "SMOKE_INVITE_CODE",
                "SMOKE_INVITE_CODES",
                "INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE",
                // U7 (Passo 23): the size of the load cycle driver, read only by the loadtest profile.
                "LOADTEST_LEDGER_PEOPLE",
                "LOADTEST_FILLS_PER_PERSON",
                "LOADTEST_CYCLE_BOTS",
                "LOADTEST_TOKENS_PER_BOT",
                // U7 review repair R-04: bots the driver closes during the u7 run.
                "LOADTEST_CLOSE_BOTS",
                // U6 (Passo 16): the switch and the size of the load risk driver, read only by the loadtest profile.
                "LOADTEST_U6_ENABLED",
                "LOADTEST_U6_REJECTIONS_PER_BOT",
                "LOADTEST_U6_HEAVY_BOT_REJECTIONS",
                "LOADTEST_U6_OLD_APPROVED",
                "LOADTEST_U6_PURGE_AT",
                // U5 (Passo 20): the switch and the size of the bot load scenario, read only by the loadtest profile.
                "LOADTEST_U5_ENABLED",
                "LOADTEST_U5_DECISIONS_PER_BOT",
                "LOADTEST_U5_PEOPLE",
                // U5 (Passo 20): the frequent-signal scenario, accepted by the test profile only.
                "MARKET_SIMULATED_SCENARIO");
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

    /**
     * U4 (infrastructure-specification.md): every variable of the table reaches the api with its default in both
     * compositions, is listed without a value in .env.example and is read by application.yml with the same default.
     */
    @Test
    @Tag("u4")
    void everyU4VariableReachesTheApiWithItsDefaultAndIsListedInTheEnvExample() throws IOException {
        Map<String, String> defaults = new java.util.LinkedHashMap<>();
        defaults.put("DB_POOL_MAX_SIZE", "20");
        // U7 (Passo 1): 11 since the four U7 jobs; U6 (Passo 1): 13, one per scheduled job plus the purge; U5: 14;
        // U8 (Passo 1): 16, the kill switch reconciliation and the feedback purge.
        defaults.put("SCHEDULER_POOL_SIZE", "16");
        defaults.put("MARKET_SOURCES", "sim-a,sim-b");
        defaults.put("MARKET_COLLECTION_INTERVAL", "30s");
        defaults.put("MARKET_SIMULATED_TOKENS_PER_SOURCE", "200");
        defaults.put("MARKET_SIMULATED_SEED", "42");
        defaults.put("MARKET_DIVERGENCE_TOLERANCE_PERCENT", "5.0000");
        defaults.put("SCANNER_RATE_LIMIT_PER_MINUTE", "60");
        String example = read(".env.example");
        String application = read("apps/api/src/main/resources/application.yml");

        for (Map<String, Map<String, Object>> services : List.of(services(false), services(true))) {
            Map<String, Object> api = environment(services, "api");
            defaults.forEach((name, value) ->
                    assertThat(String.valueOf(api.get(name))).as(name).isEqualTo("${" + name + ":-" + value + "}"));
        }
        defaults.forEach((name, value) -> {
            assertThat(example).as(name).contains("\n" + name + "=\n");
            assertThat(application).as(name).contains("${" + name + ":" + value + "}");
        });
    }

    /**
     * U3 (Infrastructure Design review finding): the overlay passes the smoke code, the load codes and the lookup
     * limit with their defaults (empty, empty, 10); the base file passes none of them, so daily use never seeds an
     * invitation nor raises the limit.
     */
    @Test
    @Tag("u3")
    void theTestOverlayPassesTheU3SmokeCodesAndTheLookupLimitWithTheirDefaults() throws IOException {
        Map<String, Object> api = environment(services(true), "api");

        assertThat(String.valueOf(api.get("SMOKE_INVITE_CODE"))).isEqualTo("${SMOKE_INVITE_CODE:-}");
        assertThat(String.valueOf(api.get("SMOKE_INVITE_CODES"))).isEqualTo("${SMOKE_INVITE_CODES:-}");
        assertThat(String.valueOf(api.get("INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE")))
                .isEqualTo("${INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE:-10}");
        assertThat(read("docker-compose.yml"))
                .doesNotContain("SMOKE_INVITE_CODE")
                .doesNotContain("INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE");
        assertThat(read(".env.example")).doesNotContain("SMOKE_INVITE_CODE");
    }

    /**
     * U7 (Passo 23): only the test overlay passes the size of the load cycle driver, with defaults that keep it idle
     * (0 people, 0 fills, 0 bots); the base file and .env.example never name it.
     */
    @Test
    @Tag("u7")
    void onlyTheTestOverlayPassesTheLoadDriverSizeAndItsDefaultsKeepItIdle() throws IOException {
        Map<String, Object> api = environment(services(true), "api");

        assertThat(String.valueOf(api.get("LOADTEST_LEDGER_PEOPLE"))).isEqualTo("${LOADTEST_LEDGER_PEOPLE:-0}");
        assertThat(String.valueOf(api.get("LOADTEST_FILLS_PER_PERSON"))).isEqualTo("${LOADTEST_FILLS_PER_PERSON:-0}");
        assertThat(String.valueOf(api.get("LOADTEST_CYCLE_BOTS"))).isEqualTo("${LOADTEST_CYCLE_BOTS:-0}");
        assertThat(String.valueOf(api.get("LOADTEST_TOKENS_PER_BOT"))).isEqualTo("${LOADTEST_TOKENS_PER_BOT:-5}");
        assertThat(String.valueOf(api.get("LOADTEST_CLOSE_BOTS"))).isEqualTo("${LOADTEST_CLOSE_BOTS:-0}");
        assertThat(read("docker-compose.yml")).doesNotContain("LOADTEST_");
        assertThat(read(".env.example")).doesNotContain("LOADTEST_");
        assertThat(read("apps/api/src/main/resources/application.yml")).doesNotContain("LOADTEST_");
        assertThat(read("apps/api/src/main/resources/application-loadtest.yml"))
                .contains("${LOADTEST_LEDGER_PEOPLE:0}")
                .contains("${LOADTEST_CLOSE_BOTS:0}");
    }

    /**
     * U6 (Passo 16, cicd-pipeline.md): only the test overlay passes the switch and the size of the load risk driver,
     * off by default; the base file, .env.example and application.yml never name them, and there is no
     * LOADTEST_U6_PEOPLE (the people of u6 come from LOADTEST_LEDGER_PEOPLE).
     */
    @Test
    @Tag("u6")
    void onlyTheTestOverlayPassesTheU6LoadVariablesAndTheDriverIsOffByDefault() throws IOException {
        Map<String, Object> api = environment(services(true), "api");

        assertThat(String.valueOf(api.get("LOADTEST_U6_ENABLED"))).isEqualTo("${LOADTEST_U6_ENABLED:-false}");
        assertThat(String.valueOf(api.get("LOADTEST_U6_REJECTIONS_PER_BOT")))
                .isEqualTo("${LOADTEST_U6_REJECTIONS_PER_BOT:-10000}");
        assertThat(String.valueOf(api.get("LOADTEST_U6_HEAVY_BOT_REJECTIONS")))
                .isEqualTo("${LOADTEST_U6_HEAVY_BOT_REJECTIONS:-80000}");
        assertThat(String.valueOf(api.get("LOADTEST_U6_OLD_APPROVED")))
                .isEqualTo("${LOADTEST_U6_OLD_APPROVED:-200000}");
        assertThat(String.valueOf(api.get("LOADTEST_U6_PURGE_AT"))).isEqualTo("${LOADTEST_U6_PURGE_AT:-300s}");
        assertThat(api).doesNotContainKey("LOADTEST_U6_PEOPLE");
        assertThat(environment(services(false), "api")).doesNotContainKey("LOADTEST_U6_ENABLED");
        assertThat(read("docker-compose.yml")).doesNotContain("LOADTEST_U6_");
        assertThat(read(".env.example")).doesNotContain("LOADTEST_U6_");
        assertThat(read("apps/api/src/main/resources/application.yml")).doesNotContain("LOADTEST_U6_");
        assertThat(read("apps/api/src/main/resources/application-loadtest.yml"))
                .contains("${LOADTEST_U6_ENABLED:false}")
                .contains("${LOADTEST_U6_PURGE_AT:300s}");
    }

    // ----- U5 (infrastructure-specification.md "Serviço worker no Compose", NFR10.9, NFR2.4) -----

    /** The worker: same image, worker profile and process, only the variables it uses, liveness probe, restart. */
    @Test
    @Tag("u5")
    @SuppressWarnings("unchecked")
    void theWorkerRunsTheSameImageWithOnlyTheVariablesItUses() throws IOException {
        for (Map<String, Map<String, Object>> services : List.of(services(false), services(true))) {
            Map<String, Object> worker = services.get("worker");
            Map<String, Object> environment = environment(services, "worker");

            assertThat(worker.get("image")).isEqualTo(services.get("api").get("image"));
            assertThat(environment)
                    .doesNotContainKeys(
                            "ACCESS_TOKEN_SIGNING_KEY",
                            "GUEST_APP_ORIGIN",
                            "TRUSTED_PROXIES",
                            "ADMIN_WALLET_ADDRESSES",
                            "DB_RETENTION_USER",
                            "DB_RETENTION_PASSWORD",
                            "DB_OWNER_USER",
                            "DB_OWNER_PASSWORD",
                            "POSTGRES_PASSWORD")
                    .containsKeys("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD", "MARKET_SOURCES");
            assertThat(String.valueOf(environment.get("MEMETRADE_PROCESS"))).isEqualTo("worker");
            assertThat(String.valueOf(environment.get("DB_POOL_MAX_SIZE"))).isEqualTo("14");
            assertThat(String.valueOf(environment.get("SCHEDULER_POOL_SIZE"))).isEqualTo("4");
            assertThat(String.valueOf(environment.get("SPRING_PROFILES_ACTIVE")))
                    .endsWith("worker");
            assertThat(dependsOn(services, "worker"))
                    .containsEntry("db-migrate", "service_completed_successfully")
                    .containsEntry("api", "service_healthy");
            assertThat(worker).containsEntry("restart", "unless-stopped").containsEntry("mem_limit", "1g");
            Map<String, Object> healthcheck = (Map<String, Object>) worker.get("healthcheck");
            assertThat(String.valueOf(healthcheck.get("test")))
                    .contains("com.memetrade.ops.HealthProbe")
                    .contains("http://127.0.0.1:8081/actuator/health/liveness");
            assertThat(healthcheck)
                    .containsEntry("interval", "10s")
                    .containsEntry("timeout", "5s")
                    .containsEntry("retries", 6)
                    .containsEntry("start_period", "60s");
        }
    }

    /** Only the test overlay publishes the worker actuator, on loopback 8082, and adds the test profile. */
    @Test
    @Tag("u5")
    @SuppressWarnings("unchecked")
    void onlyTheTestOverlayPublishesTheWorkerPortOnLoopback() throws IOException {
        assertThat(services(false).get("worker")).doesNotContainKey("ports");
        Map<String, Object> worker = services(true).get("worker");

        assertThat((List<String>) worker.get("ports")).containsExactly("127.0.0.1:8082:8081");
        assertThat(String.valueOf(environment(services(true), "worker").get("SPRING_PROFILES_ACTIVE")))
                .isEqualTo("${SPRING_PROFILES_ACTIVE:-test},worker");
        assertThat(String.valueOf(environment(services(true), "api").get("LOADTEST_U5_ENABLED")))
                .isEqualTo("${LOADTEST_U5_ENABLED:-false}");
        assertThat(read("docker-compose.yml")).doesNotContain("8082").doesNotContain("LOADTEST_U5_");
    }

    /** NFR2.4: the connection tally written in docker-compose.yml fits the max_connections of postgres:17 (100). */
    @Test
    @Tag("u5")
    void theConnectionTallyFitsTheDatabaseLimit() throws IOException {
        int api = 20;
        int trailRetention = 1;
        int riskRetention = 1;
        // U8 (infrastructure-specification.md, review R-08): the dedicated pool of the apply lock and the pool of the
        // feedback retention, 1 connection each.
        int applyLock = 1;
        int feedbackRetention = 1;
        int worker = 14;
        int startUp = 2;
        int superuserReserved = 3;
        int total = api
                + trailRetention
                + riskRetention
                + applyLock
                + feedbackRetention
                + worker
                + startUp
                + superuserReserved;

        assertThat(total).isEqualTo(43).isLessThanOrEqualTo(100);
        assertThat(read("docker-compose.yml"))
                .contains("api 20 + trail retention (U2) 1 + U6 retention 1")
                .contains("+ U8 apply lock 1 + U8 feedback retention 1 + worker 14")
                .contains("+ db-bootstrap and db-migrate at start-up 2 + 3 reserved")
                .contains("for the superuser = 43");
    }

    // ----- u1-legal-documents (infrastructure-specification.md "Rede" and "Configuração nova da api", NFR6.3) -----

    /** The network has a fixed, parameterised range with an explicit gateway and a dynamic range outside the apps. */
    @Test
    @Tag("u1-legal-documents")
    @SuppressWarnings("unchecked")
    void theNetworkHasAFixedRangeAGatewayAtOneAndADynamicRangeOutsideTheApps() throws IOException {
        Map<String, Object> networks =
                (Map<String, Object>) yaml("docker-compose.yml").get("networks");
        Map<String, Object> ipam = (Map<String, Object>) ((Map<String, Object>) networks.get("default")).get("ipam");
        Map<String, Object> config = ((List<Map<String, Object>>) ipam.get("config")).getFirst();

        assertThat(config)
                .containsEntry("subnet", "${COMPOSE_SUBNET:-172.28.0.0/16}")
                .containsEntry("gateway", "${COMPOSE_GATEWAY:-172.28.0.1}")
                .containsEntry("ip_range", "${COMPOSE_DYNAMIC_RANGE:-172.28.0.0/24}");
        com.memetrade.identity.domain.ratelimit.InternalNetworks apps =
                com.memetrade.identity.domain.ratelimit.InternalNetworks.parse(List.of("172.28.1.0/24"));
        com.memetrade.identity.domain.ratelimit.InternalNetworks dynamic =
                com.memetrade.identity.domain.ratelimit.InternalNetworks.parse(List.of("172.28.0.0/24"));
        assertThat(apps.contains("172.28.0.1"))
                .as("the gateway is never exempt")
                .isFalse();
        assertThat(dynamic.contains("172.28.1.10")).isFalse();
        assertThat(dynamic.contains("172.28.1.11")).isFalse();
        assertThat(com.memetrade.identity.domain.ratelimit.InternalNetworks.parse(List.of("172.28.0.0/16"))
                        .contains("172.28.1.10"))
                .isTrue();
    }

    /** web and admin have fixed addresses in the apps sub-range and read the API inside the network, on the server. */
    @Test
    @Tag("u1-legal-documents")
    @SuppressWarnings("unchecked")
    void webAndAdminHaveFixedAddressesInTheAppsSubRangeAndAnInternalApiOrigin() throws IOException {
        com.memetrade.identity.domain.ratelimit.InternalNetworks apps =
                com.memetrade.identity.domain.ratelimit.InternalNetworks.parse(List.of("172.28.1.0/24"));
        for (Map<String, Map<String, Object>> services : List.of(services(false), services(true))) {
            for (Map.Entry<String, String> app : Map.of(
                            "web", "${COMPOSE_WEB_IPV4:-172.28.1.10}", "admin", "${COMPOSE_ADMIN_IPV4:-172.28.1.11}")
                    .entrySet()) {
                Map<String, Object> network = (Map<String, Object>)
                        ((Map<String, Object>) services.get(app.getKey()).get("networks")).get("default");
                String address = String.valueOf(network.get("ipv4_address"));
                assertThat(address).as(app.getKey()).isEqualTo(app.getValue());
                assertThat(apps.contains(address.replaceAll("^\\$\\{[A-Z0-9_]+:-|}$", "")))
                        .as(app.getKey())
                        .isTrue();
                assertThat(String.valueOf(environment(services, app.getKey()).get("API_INTERNAL_ORIGIN")))
                        .isEqualTo("${API_INTERNAL_ORIGIN:-http://api:8080}");
                assertThat(String.valueOf(services.get(app.getKey()))).doesNotContain("NEXT_PUBLIC");
            }
        }
    }

    /** The api receives the legal settings with their defaults; the per-minute limit is never an env variable here. */
    @Test
    @Tag("u1-legal-documents")
    void theApiReceivesTheLegalSettingsAndNeverTheLimitThatWouldHideTheLoadtestValue() throws IOException {
        Map<String, String> defaults = new java.util.LinkedHashMap<>();
        defaults.put("MEMETRADE_LEGAL_STORAGE_LOCATION", "${MEMETRADE_LEGAL_STORAGE_LOCATION:-}");
        defaults.put("MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER", "${MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER:-}");
        defaults.put("MEMETRADE_LEGAL_DPO_EMAIL", "${MEMETRADE_LEGAL_DPO_EMAIL:-}");
        defaults.put("MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE", "${MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE:-false}");
        defaults.put(
                "MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS",
                "${MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS:-${COMPOSE_APPS_SUBNET:-172.28.1.0/24}}");
        for (Map<String, Map<String, Object>> services : List.of(services(false), services(true))) {
            Map<String, Object> api = environment(services, "api");
            defaults.forEach((name, value) ->
                    assertThat(String.valueOf(api.get(name))).as(name).isEqualTo(value));
            assertThat(api).doesNotContainKey("MEMETRADE_LEGAL_RATE_LIMIT_PER_MINUTE");
            assertThat(environment(services, "worker"))
                    .doesNotContainKeys(defaults.keySet().toArray(String[]::new));
        }
        String example = read(".env.example");
        for (String name : List.of(
                "MEMETRADE_LEGAL_STORAGE_LOCATION",
                "MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER",
                "MEMETRADE_LEGAL_DPO_EMAIL",
                "MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE",
                "MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS",
                "COMPOSE_SUBNET",
                "COMPOSE_APPS_SUBNET",
                "API_INTERNAL_ORIGIN")) {
            assertThat(example).as(name).contains("\n" + name + "=\n");
        }
    }

    /** Only the test overlay points the api at the test catalog, mounted read-only from the test resources. */
    @Test
    @Tag("u1-legal-documents")
    @SuppressWarnings("unchecked")
    void onlyTheTestOverlayMountsTheTestCatalogReadOnly() throws IOException {
        Map<String, Object> api = services(true).get("api");

        assertThat(String.valueOf(environment(services(true), "api").get("MEMETRADE_TERMS_LOCATION")))
                .isEqualTo("file:/app/terms-test/");
        assertThat((List<String>) api.get("volumes"))
                .containsExactly("./apps/api/src/test/resources/terms-test/reviewed:/app/terms-test:ro");
        assertThat(services(false).get("api")).doesNotContainKey("volumes");
        assertThat(read("docker-compose.yml")).doesNotContain("terms-test").doesNotContain("MEMETRADE_TERMS_LOCATION");
        assertThat(Files.exists(ROOT.resolve("apps/api/src/test/resources/terms-test/reviewed/v2/version.properties")))
                .isTrue();
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
