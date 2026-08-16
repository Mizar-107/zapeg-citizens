package io.github.mizar107.zapegcitizens.compat.brain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PendingToolResultsTest {

    private static final String ASYNC_ACCEPTED = """
            {"success":true,"message":"accepted","data":{"task_id":"t1","async":true}}
            """;

    @Test
    void keepsAsyncAcceptancePendingUntilTerminalResult() {
        PendingToolResults router = new PendingToolResults();
        List<String> delivered = new ArrayList<>();
        String id = "mcp-zapeg-1";

        assertTrue(router.register(id, UUID.randomUUID(), delivered::add));
        assertTrue(router.acceptImmediate(id, ASYNC_ACCEPTED));
        assertEquals(1, router.size());
        assertTrue(delivered.isEmpty());

        assertTrue(router.acceptTerminal(id, "{\"success\":true}"));
        assertEquals(List.of("{\"success\":true}"), delivered);
        assertEquals(0, router.size());
    }

    @Test
    void deliversImmediateAndTerminalResultsAtMostOnce() {
        PendingToolResults router = new PendingToolResults();
        List<String> delivered = new ArrayList<>();
        String id = "mcp-zapeg-2";

        assertTrue(router.register(id, UUID.randomUUID(), delivered::add));
        assertTrue(router.acceptImmediate(id, "{\"success\":false}"));
        assertFalse(router.acceptTerminal(id, "{\"success\":true}"));
        assertEquals(List.of("{\"success\":false}"), delivered);
    }

    @Test
    void cancellationRemovesOneCallOrEveryCallForABody() {
        PendingToolResults router = new PendingToolResults();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        router.register("mcp-zapeg-a", first, ignored -> {});
        router.register("mcp-zapeg-b", first, ignored -> {});
        router.register("mcp-zapeg-c", second, ignored -> {});

        assertTrue(router.cancel("mcp-zapeg-a"));
        assertEquals(1, router.cancelBody(first));
        assertEquals(1, router.size());
        assertFalse(router.cancel("missing"));
    }
}
