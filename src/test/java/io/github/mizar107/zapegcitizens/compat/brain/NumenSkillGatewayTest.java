package io.github.mizar107.zapegcitizens.compat.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NumenSkillGatewayTest {

    @Test
    void publishesAClosedLoadSkillSchema() {
        JsonObject definition = NumenSkillGateway.toolDefinition();
        assertEquals("function", definition.get("type").getAsString());
        JsonObject function = definition.getAsJsonObject("function");
        assertEquals("load_skill", function.get("name").getAsString());

        JsonObject parameters = function.getAsJsonObject("parameters");
        assertFalse(parameters.get("additionalProperties").getAsBoolean());
        JsonArray names = parameters.getAsJsonObject("properties")
                .getAsJsonObject("name")
                .getAsJsonArray("enum");
        assertEquals(
                List.of("storage", "building", "mining", "combat"),
                names.asList().stream().map(element -> element.getAsString()).toList());
    }

    @Test
    void returnsTrustedWorkflowContentAsANormalImmediateToolResult() {
        JsonObject args = new JsonObject();
        args.addProperty("name", "mining");

        JsonObject result = JsonParser.parseString(NumenSkillGateway.execute(args)).getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("mining", result.getAsJsonObject("data").get("name").getAsString());
        assertTrue(result.getAsJsonObject("data").get("workflow").getAsString()
                .contains("deepslate_diamond_ore"));
    }

    @Test
    void rejectsUnknownNamesAndExtraArguments() {
        JsonObject unknown = new JsonObject();
        unknown.addProperty("name", "../secrets");
        assertThrows(IllegalArgumentException.class, () -> NumenSkillGateway.execute(unknown));

        JsonObject extra = new JsonObject();
        extra.addProperty("name", "storage");
        extra.addProperty("file", "anything");
        assertThrows(IllegalArgumentException.class, () -> NumenSkillGateway.execute(extra));
    }
}
