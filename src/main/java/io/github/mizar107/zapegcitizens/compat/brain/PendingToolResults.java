package io.github.mizar107.zapegcitizens.compat.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Thread-safe, at-most-once routing for server-side Numen tool results. */
public final class PendingToolResults {

    private final ConcurrentMap<String, PendingResult> pending = new ConcurrentHashMap<>();

    public boolean register(String toolCallId, UUID citizenId, Consumer<String> result) {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(citizenId, "citizenId");
        Objects.requireNonNull(result, "result");
        return pending.putIfAbsent(toolCallId, new PendingResult(citizenId, result)) == null;
    }

    /**
     * Accept a direct {@code onServerCall} reply. Async acceptance is deliberately
     * swallowed and kept pending; the task-completion mixin will provide the real
     * terminal result instead of making the shared brain poll Numen.
     */
    public boolean acceptImmediate(String toolCallId, String resultJson) {
        if (!pending.containsKey(toolCallId)) {
            return false;
        }
        if (isAsyncAcceptance(resultJson)) {
            return true;
        }
        return finish(toolCallId, resultJson);
    }

    public boolean acceptTerminal(String toolCallId, String resultJson) {
        return finish(toolCallId, resultJson);
    }

    public boolean cancel(String toolCallId) {
        return toolCallId != null && pending.remove(toolCallId) != null;
    }

    public int cancelBody(UUID citizenId) {
        if (citizenId == null) {
            return 0;
        }
        int removed = 0;
        for (var entry : pending.entrySet()) {
            PendingResult value = entry.getValue();
            if (citizenId.equals(value.citizenId())
                    && pending.remove(entry.getKey(), value)) {
                removed++;
            }
        }
        return removed;
    }

    public int size() {
        return pending.size();
    }

    /** Server-stopping cleanup: drop every pending callback without invoking it. */
    public void clear() {
        pending.clear();
    }

    static boolean isAsyncAcceptance(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                return false;
            }
            if (!root.has("data") || !root.get("data").isJsonObject()) {
                return false;
            }
            JsonObject data = root.getAsJsonObject("data");
            return data.has("async") && data.get("async").getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean finish(String toolCallId, String resultJson) {
        if (toolCallId == null) {
            return false;
        }
        PendingResult result = pending.remove(toolCallId);
        if (result == null) {
            return false;
        }
        try {
            result.sink().accept(resultJson);
        } catch (RuntimeException ignored) {
            // The result is still considered consumed. Retrying an arbitrary sink
            // would violate the LLM tool protocol's exactly-one-result contract.
        }
        return true;
    }

    private record PendingResult(UUID citizenId, Consumer<String> sink) {}
}
