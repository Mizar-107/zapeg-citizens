package io.github.mizar107.zapegcitizens.brain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainConfigTest {

    private static final String[] PROPERTIES = {
            "CITIZENS_BRAIN_URL",
            "CITIZENS_BRAIN_TOKEN",
            "CITIZENS_BRAIN_TOKEN_FILE",
            "CITIZENS_BRAIN_CONNECT_TIMEOUT_MS",
            "CITIZENS_BRAIN_REQUEST_TIMEOUT_MS",
            "CITIZENS_BRAIN_TURN_TIMEOUT_MS",
            "CITIZENS_BRAIN_MAX_TOOL_STEPS",
            "CITIZENS_JOB_MAX_ACTIONS",
            "CITIZENS_JOB_MAX_MODEL_CALLS",
            "CITIZENS_JOB_MAX_ACTIVE_SECONDS"
    };

    @AfterEach
    void clearProperties() {
        for (String property : PROPERTIES) {
            System.clearProperty(property);
        }
    }

    @Test
    void remainsDisabledWhenNoUrlIsConfigured() {
        System.setProperty("CITIZENS_BRAIN_URL", " ");
        assertTrue(BrainConfig.fromEnvironment().isEmpty());
    }

    @Test
    void readsAndBoundsHostConfiguration() {
        System.setProperty("CITIZENS_BRAIN_URL", "http://citizen-brain:8787/");
        System.setProperty("CITIZENS_BRAIN_TOKEN", "bridge-secret");
        System.setProperty("CITIZENS_BRAIN_MAX_TOOL_STEPS", "7");
        System.setProperty("CITIZENS_BRAIN_TURN_TIMEOUT_MS", "45000");
        System.setProperty("CITIZENS_JOB_MAX_ACTIONS", "144");
        System.setProperty("CITIZENS_JOB_MAX_MODEL_CALLS", "233");
        System.setProperty("CITIZENS_JOB_MAX_ACTIVE_SECONDS", "7200");

        BrainConfig config = BrainConfig.fromEnvironment().orElseThrow();

        assertEquals("http://citizen-brain:8787", config.baseUri().toString());
        assertEquals(7, config.maxToolSteps());
        assertEquals(45_000, config.turnTimeout().toMillis());
        assertEquals(144, config.maxJobActions());
        assertEquals(233, config.maxJobModelCalls());
        assertEquals(7_200, config.maxJobActiveTime().toSeconds());
        assertEquals("http://citizen-brain:8787/v1/turn/start",
                config.endpoint("/v1/turn/start").toString());
    }

    @Test
    void failsClosedForMissingTokenOrUnsafeScheme() {
        System.setProperty("CITIZENS_BRAIN_URL", "http://citizen-brain:8787");
        assertThrows(IllegalStateException.class, BrainConfig::fromEnvironment);

        System.setProperty("CITIZENS_BRAIN_TOKEN", "bridge-secret");
        System.setProperty("CITIZENS_BRAIN_URL", "file:///tmp/brain");
        assertThrows(IllegalStateException.class, BrainConfig::fromEnvironment);
    }

    @Test
    void rejectsBearerTokensContainingHeaderControlCharacters() {
        System.setProperty("CITIZENS_BRAIN_URL", "http://citizen-brain:8787");
        System.setProperty("CITIZENS_BRAIN_TOKEN", "bridge-secret\r\nX-Injected: yes");

        assertThrows(IllegalStateException.class, BrainConfig::fromEnvironment);

        System.setProperty("CITIZENS_BRAIN_TOKEN", "bridge-secret\u0000suffix");
        assertThrows(IllegalStateException.class, BrainConfig::fromEnvironment);
    }

    @Test
    void rejectsNonOriginUrlsAndInvalidPorts() {
        System.setProperty("CITIZENS_BRAIN_TOKEN", "bridge-secret");
        System.setProperty("CITIZENS_BRAIN_URL", "http://citizen-brain:8787/base");
        assertThrows(IllegalStateException.class, BrainConfig::fromEnvironment);

        System.setProperty("CITIZENS_BRAIN_URL", "http://citizen-brain:99999");
        assertThrows(IllegalStateException.class, BrainConfig::fromEnvironment);
    }
}
