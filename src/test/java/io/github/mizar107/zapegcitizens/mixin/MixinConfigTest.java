package io.github.mizar107.zapegcitizens.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MixinConfigTest {

    @Test
    void requiredConfigIncludesManagedClientCancelGuard() throws Exception {
        try (var stream = MixinConfigTest.class.getResourceAsStream(
                "/zapeg_citizens.mixins.json")) {
            assertTrue(stream != null);
            JsonObject config = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.get("required").getAsBoolean());
            JsonArray mixins = config.getAsJsonArray("mixins");
            assertTrue(mixins.asList().stream().anyMatch(element ->
                    "ManagedCitizenCancelTasksPayloadMixin".equals(element.getAsString())));
        }
    }
}
