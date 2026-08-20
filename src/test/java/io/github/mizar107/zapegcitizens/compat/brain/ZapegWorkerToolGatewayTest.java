package io.github.mizar107.zapegcitizens.compat.brain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema and argument-validation coverage for the deterministic ZapeG worker
 * primitives. Selection/equip behavior needs live registries and a body, so it
 * remains covered by the server smoke test rather than a unit test.
 */
final class ZapegWorkerToolGatewayTest {

    @Test
    void publishesAClosedEquipBestToolSchema() {
        JsonObject definition = ZapegWorkerToolGateway.toolDefinition();
        assertEquals("function", definition.get("type").getAsString());
        JsonObject function = definition.getAsJsonObject("function");
        assertEquals("equip_best_tool", function.get("name").getAsString());
        String description = function.get("description").getAsString();
        assertTrue(description.contains("missing_tool"));
        assertTrue(description.contains("job_needs_input"));
        assertTrue(description.contains("hand"));

        JsonObject parameters = function.getAsJsonObject("parameters");
        assertFalse(parameters.get("additionalProperties").getAsBoolean());
        assertTrue(parameters.getAsJsonObject("properties").has("block_id"));
        assertEquals(
                "block_id",
                parameters.getAsJsonArray("required").get(0).getAsString());
    }

    @Test
    void handlesOnlyItsOwnToolName() {
        assertTrue(ZapegWorkerToolGateway.handles("equip_best_tool"));
        assertFalse(ZapegWorkerToolGateway.handles("equip_item"));
        assertFalse(ZapegWorkerToolGateway.handles("load_skill"));
        assertFalse(ZapegWorkerToolGateway.handles(null));
    }

    @Test
    void rejectsMissingBodyAndMalformedArgumentsBeforeTouchingTheWorld() {
        assertThrows(IllegalArgumentException.class,
                () -> ZapegWorkerToolGateway.execute(null, new JsonObject()));
    }
}
