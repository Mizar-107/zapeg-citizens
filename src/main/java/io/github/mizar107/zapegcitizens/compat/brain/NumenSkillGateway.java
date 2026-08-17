package io.github.mizar107.zapegcitizens.compat.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mizar107.zapegcitizens.skill.JobSkillCatalog;

/** Server-side replacement for Numen's client-local {@code load_skill} helper. */
public final class NumenSkillGateway {

    public static final String TOOL_NAME = "load_skill";

    private NumenSkillGateway() {}

    public static boolean handles(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    /** OpenAI/Ollama-compatible definition with a closed, path-free allowlist. */
    public static JsonObject toolDefinition() {
        JsonArray names = new JsonArray();
        JobSkillCatalog.names().forEach(names::add);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description",
                "Trusted workflow to load before a complex storage, building, mining, or combat job.");
        name.add("enum", names);

        JsonObject properties = new JsonObject();
        properties.add("name", name);

        JsonArray required = new JsonArray();
        required.add("name");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", required);
        parameters.addProperty("additionalProperties", false);

        JsonObject function = new JsonObject();
        function.addProperty("name", TOOL_NAME);
        function.addProperty("description",
                "Load one trusted ZapeG server workflow into this task. Call it before planning "
                        + "non-trivial storage, building, mining, or combat work. The name is a fixed "
                        + "allowlist; arbitrary files and paths are never accepted.");
        function.add("parameters", parameters);

        JsonObject definition = new JsonObject();
        definition.addProperty("type", "function");
        definition.add("function", function);
        return definition;
    }

    /** Execute the synthetic helper without entering Numen's client-only skill registry. */
    public static String execute(JsonObject args) {
        if (args == null || !args.has("name") || !args.get("name").isJsonPrimitive()
                || !args.getAsJsonPrimitive("name").isString()) {
            throw new IllegalArgumentException("load_skill requires a string name");
        }
        if (args.size() != 1) {
            throw new IllegalArgumentException("load_skill accepts only the name field");
        }
        String name = args.get("name").getAsString();
        String workflow = JobSkillCatalog.require(name);

        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        data.addProperty("workflow", workflow);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty(
                "message", "Loaded trusted '" + name + "' workflow. Follow it for this job.");
        result.add("data", data);
        return result.toString();
    }
}
