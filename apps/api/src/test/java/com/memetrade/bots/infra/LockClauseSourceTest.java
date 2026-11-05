package com.memetrade.bots.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * NFR10.4 (reliability-design.md, Passo 8, written before LockOrder): a SOURCE test, not ArchUnit. Every row lock of
 * the bots and decision modules is taken by {@code LockOrder}, so the global order of BR2.11 is the only one in the
 * code: no {@code FOR UPDATE}, {@code FOR SHARE} nor {@code FOR NO KEY UPDATE} anywhere else.
 */
@Tag("u5")
class LockClauseSourceTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "memetrade");
    private static final List<String> CLAUSES = List.of("for update", "for share", "for no key update");

    @Test
    void onlyLockOrderWritesALockClauseInTheBotsAndDecisionModules() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files =
                Stream.concat(Files.walk(MAIN.resolve("bots")), Files.walk(MAIN.resolve("decision")))) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("LockOrder.java"))
                    .filter(LockClauseSourceTest::hasLockClause)
                    .toList();
        }

        assertThat(offenders).isEmpty();
    }

    @Test
    void lockOrderHoldsTheFourLocksOfTheDesign() throws IOException {
        String source =
                Files.readString(MAIN.resolve(Path.of("bots", "infra", "LockOrder.java")), StandardCharsets.UTF_8);

        assertThat(source)
                .contains("lockSystemPauseShared")
                .contains("lockSystemPauseExclusive")
                .contains("lockOwner")
                .contains("lockBot");
        assertThat(source.toLowerCase(Locale.ROOT)).contains("for share").contains("for update");
    }

    private static boolean hasLockClause(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
            return CLAUSES.stream().anyMatch(text::contains);
        } catch (IOException unreadable) {
            throw new IllegalStateException("Cannot read " + path, unreadable);
        }
    }
}
