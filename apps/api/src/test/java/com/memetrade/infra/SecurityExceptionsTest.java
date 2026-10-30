package com.memetrade.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Security exceptions need justification, approval and an expiry of at most 90 days; an expired exception blocks
 * the build again (team.md "Segurança na CI").
 */
@Tag("u1")
class SecurityExceptionsTest {

    private static final Path REGISTER = Path.of("..", "..", "security", "exceptions.yml");
    private static final List<String> TOOLS = List.of("dependency-check", "codeql", "pnpm-audit", "gitleaks");

    @Test
    void theVersionedRegisterHasNoInvalidOrExpiredException() throws IOException {
        try (InputStream in = Files.newInputStream(REGISTER)) {
            Map<String, Object> yaml = new Yaml().load(in);
            assertThat(problems(yaml, LocalDate.now(ZoneOffset.UTC))).isEmpty();
        }
    }

    @Test
    void anExpiredExceptionBlocksAgain() {
        Map<String, Object> register = Map.of("exceptions", List.of(entry("2026-01-01", "2026-03-01")));

        assertThat(problems(register, LocalDate.parse("2026-03-02"))).containsExactly("EX-1: expired on 2026-03-01");
        assertThat(problems(register, LocalDate.parse("2026-03-01"))).isEmpty();
    }

    @Test
    void anExceptionLongerThan90DaysOrWithoutApprovalIsRefused() {
        Map<String, Object> tooLong = entry("2026-01-01", "2026-06-01");
        Map<String, Object> unapproved = new java.util.HashMap<>(entry("2026-01-01", "2026-02-01"));
        unapproved.remove("approvedBy");
        unapproved.put("justification", " ");

        assertThat(problems(Map.of("exceptions", List.of(tooLong, unapproved)), LocalDate.parse("2026-01-15")))
                .containsExactlyInAnyOrder(
                        "EX-1: expiry beyond 90 days", "EX-1: approvedBy missing", "EX-1: justification missing");
    }

    @SuppressWarnings("unchecked")
    static List<String> problems(Map<String, Object> register, LocalDate today) {
        List<String> problems = new ArrayList<>();
        Object list = register == null ? null : register.get("exceptions");
        if (!(list instanceof List<?> exceptions)) {
            problems.add("exceptions list missing");
            return problems;
        }
        for (Object item : exceptions) {
            Map<String, Object> e = (Map<String, Object>) item;
            String id = String.valueOf(e.get("id"));
            for (String field : List.of("tool", "finding", "justification", "approvedBy", "approvedAt", "expires")) {
                if (e.get(field) == null || String.valueOf(e.get(field)).isBlank()) {
                    problems.add(id + ": " + field + " missing");
                }
            }
            if (e.get("tool") != null && !TOOLS.contains(String.valueOf(e.get("tool")))) {
                problems.add(id + ": unknown tool");
            }
            if (e.get("approvedAt") == null || e.get("expires") == null) {
                continue;
            }
            LocalDate approved = LocalDate.parse(String.valueOf(e.get("approvedAt")));
            LocalDate expires = LocalDate.parse(String.valueOf(e.get("expires")));
            if (ChronoUnit.DAYS.between(approved, expires) > 90) {
                problems.add(id + ": expiry beyond 90 days");
            }
            if (today.isAfter(expires)) {
                problems.add(id + ": expired on " + expires);
            }
        }
        return problems;
    }

    private static Map<String, Object> entry(String approvedAt, String expires) {
        return Map.of(
                "id", "EX-1",
                "tool", "dependency-check",
                "finding", "CVE-2099-0001",
                "justification", "Not reachable.",
                "approvedBy", "project-owner",
                "approvedAt", approvedAt,
                "expires", expires);
    }
}
