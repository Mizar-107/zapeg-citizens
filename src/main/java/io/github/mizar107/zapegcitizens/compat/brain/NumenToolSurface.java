package io.github.mizar107.zapegcitizens.compat.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;

/** Truthful model-facing descriptions and results for the pinned Numen subset. */
final class NumenToolSurface {

    private static final Map<String, String> DESCRIPTION_REPLACEMENTS = Map.of(
            "break_block", "interact_at with button=left and hold_ticks=-1",
            "deposit_items", "interact_at plus transfer on an open container",
            "place_block", "build with a one-cell set op");

    private NumenToolSurface() {}

    static String description(String toolName, String nativeDescription) {
        String truthful = replaceUnavailableNames(nativeDescription);
        if ("build".equals(toolName)) {
            truthful = truthful.replace(
                    "Load the building_design skill (load_skill) BEFORE designing anything non-trivial.",
                    "Call load_skill with name=building BEFORE designing anything non-trivial.");
        }
        return truthful;
    }

    /**
     * Rewrite teaching text inside Numen result JSON as well as descriptions.
     * Pinned 0.1.1 can mention old tool names in validation/failure messages.
     */
    static String result(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return resultJson;
        }
        try {
            JsonElement root = JsonParser.parseString(resultJson);
            sanitize(root);
            return root.toString();
        } catch (RuntimeException ignored) {
            return replaceUnavailableNames(resultJson);
        }
    }

    static String replaceUnavailableNames(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String rewritten = value;
        for (Map.Entry<String, String> replacement : DESCRIPTION_REPLACEMENTS.entrySet()) {
            rewritten = rewritten.replace(replacement.getKey(), replacement.getValue());
        }
        return rewritten;
    }

    private static void sanitize(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            // Snapshot the entries before replacing values in the object. This avoids depending
            // on the implementation-specific mutation tolerance of Gson's entry-set iterator.
            for (Map.Entry<String, JsonElement> entry : List.copyOf(object.entrySet())) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    object.add(entry.getKey(), new JsonPrimitive(
                            replaceUnavailableNames(value.getAsString())));
                } else {
                    sanitize(value);
                }
            }
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                JsonElement value = array.get(index);
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    array.set(index, new JsonPrimitive(replaceUnavailableNames(value.getAsString())));
                } else {
                    sanitize(value);
                }
            }
        }
    }
}
