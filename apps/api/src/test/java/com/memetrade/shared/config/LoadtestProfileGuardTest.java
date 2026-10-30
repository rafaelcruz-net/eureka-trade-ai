package com.memetrade.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("u1")
class LoadtestProfileGuardTest {

    @Test
    void refusesLoadtestWithoutTestProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("loadtest");

        assertThatThrownBy(() -> new LoadtestProfileGuard(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'test'");
    }

    @Test
    void acceptsLoadtestTogetherWithTest() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test", "loadtest");

        assertThatCode(() -> new LoadtestProfileGuard(env)).doesNotThrowAnyException();
    }

    @Test
    void ignoresRunsWithoutLoadtest() {
        assertThatCode(() -> LoadtestProfileGuard.verify(new String[] {"test"})).doesNotThrowAnyException();
        assertThatCode(() -> LoadtestProfileGuard.verify(new String[0])).doesNotThrowAnyException();
    }
}
