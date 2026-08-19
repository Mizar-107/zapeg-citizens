package io.github.mizar107.zapegcitizens.compat.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NumenToolSurfaceTest {

    @Test
    void pointsBuildAtTheServerSideTrustedSkill() {
        String nativeDescription = "Load the building_design skill (load_skill) BEFORE designing "
                + "anything non-trivial. Then place_block carefully.";

        String rewritten = NumenToolSurface.description("build", nativeDescription);

        assertTrue(rewritten.contains("load_skill with name=building"));
        assertTrue(rewritten.contains("build with a one-cell set op"));
        assertFalse(rewritten.contains("building_design skill"));
        assertFalse(rewritten.contains("place_block"));
    }

    @Test
    void removesUnavailableNamesFromDescriptionsAndNestedResults() {
        String description = NumenToolSurface.description(
                "mine", "Use break_block, then deposit_items or place_block.");
        assertFalse(description.contains("break_block"));
        assertFalse(description.contains("deposit_items"));
        assertFalse(description.contains("place_block"));
        assertTrue(description.contains("interact_at"));
        assertTrue(description.contains("transfer"));
        assertTrue(description.contains("punched"));
        assertTrue(description.contains("never a blocker"));

        String raw = """
                {"success":false,"message":"try place_block",\
                 "data":{"tips":["break_block","deposit_items"]}}
                """;
        String sanitized = NumenToolSurface.result(raw);
        assertFalse(sanitized.contains("break_block"));
        assertFalse(sanitized.contains("deposit_items"));
        assertFalse(sanitized.contains("place_block"));

        JsonObject result = JsonParser.parseString(sanitized).getAsJsonObject();
        assertTrue(result.get("message").getAsString().contains("one-cell set"));
        assertTrue(result.getAsJsonObject("data").getAsJsonArray("tips")
                .get(0).getAsString().contains("interact_at"));
    }
}
