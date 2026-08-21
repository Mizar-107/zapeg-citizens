package io.github.mizar107.zapegcitizens.compat.brain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NumenToolGatewayLimitsTest {

    @Test
    void grantsOnlyBuildTheLargerArgumentBudget() {
        assertEquals(256 * 1024, NumenToolGateway.argumentLimitFor("build"));
        assertEquals(16 * 1024, NumenToolGateway.argumentLimitFor("blueprint"));
        assertEquals(16 * 1024, NumenToolGateway.argumentLimitFor("transfer"));
        assertEquals(16 * 1024, NumenToolGateway.argumentLimitFor("load_skill"));

        JsonObject medium = argsWithPayload("x".repeat(20_000));
        assertTrue(NumenToolGateway.argumentsFit("build", medium));
        assertFalse(NumenToolGateway.argumentsFit("mine", medium));

        JsonObject excessive = argsWithPayload("x".repeat(270_000));
        assertFalse(NumenToolGateway.argumentsFit("build", excessive));
    }

    @Test
    void measuresSerializedUtf8Bytes() {
        JsonObject unicode = argsWithPayload("é".repeat(9_000));
        assertEquals(
                unicode.toString().getBytes(StandardCharsets.UTF_8).length,
                NumenToolGateway.argumentSizeBytes(unicode));
        assertTrue(NumenToolGateway.argumentSizeBytes(unicode) > 16 * 1024);
        assertFalse(NumenToolGateway.argumentsFit("transfer", unicode));
        assertTrue(NumenToolGateway.argumentsFit("build", unicode));
    }

    @Test
    void keepsGeneratedExecutionIdsWithinThePinned128CharacterContract() {
        String generated = NumenToolGateway.TOOL_CALL_PREFIX + UUID.randomUUID() + "-64";
        assertTrue(NumenToolGateway.isValidToolCallId(generated));

        String exactly128 = NumenToolGateway.TOOL_CALL_PREFIX
                + "x".repeat(128 - NumenToolGateway.TOOL_CALL_PREFIX.length());
        assertEquals(128, exactly128.length());
        assertTrue(NumenToolGateway.isValidToolCallId(exactly128));
        assertFalse(NumenToolGateway.isValidToolCallId(exactly128 + "x"));
        assertFalse(NumenToolGateway.isValidToolCallId(NumenToolGateway.TOOL_CALL_PREFIX));
        assertFalse(NumenToolGateway.isValidToolCallId("other-" + UUID.randomUUID()));
    }

    @Test
    void collectsAttackTargetIdentityTokensFromNestedArguments() {
        JsonObject args = new JsonObject();
        args.addProperty("target", "  Alice  ");
        args.addProperty("entity_id", 4711);
        JsonObject nested = new JsonObject();
        nested.addProperty("name", "ZOMBIE");
        nested.addProperty("uuid", "0F4A9C1D-2222-3333-4444-555566667777");
        nested.addProperty("distance", 3.5);
        args.add("candidate", nested);

        var texts = NumenToolGateway.argumentIdentityTokens(args);
        assertTrue(texts.contains("alice"));
        assertTrue(texts.contains("zombie"));
        assertTrue(texts.contains("0f4a9c1d-2222-3333-4444-555566667777"));

        var numbers = NumenToolGateway.argumentNumericTokens(args);
        assertTrue(numbers.contains(4711L));
        // Non-integral numbers (coordinates, distances) are never entity ids.
        assertFalse(numbers.contains(3L));
        assertFalse(numbers.contains(4L));
    }

    @Test
    void playerIdentityMatchingIsExactByNameUuidOrEntityId() {
        UUID playerId = UUID.fromString("0f4a9c1d-2222-3333-4444-555566667777");
        var texts = java.util.Set.of("alice", "zombie");
        var numbers = java.util.Set.of(4711L);

        assertTrue(NumenToolGateway.referencesIdentity(
                texts, numbers, "Alice", UUID.randomUUID(), 1));
        assertTrue(NumenToolGateway.referencesIdentity(
                java.util.Set.of("0f4a9c1d-2222-3333-4444-555566667777"),
                java.util.Set.of(), "Bob", playerId, 1));
        assertTrue(NumenToolGateway.referencesIdentity(
                java.util.Set.of(), numbers, "Bob", UUID.randomUUID(), 4711));

        // "alicia" containing "alice"-like text or other ids never match.
        assertFalse(NumenToolGateway.referencesIdentity(
                texts, numbers, "Alicia", UUID.randomUUID(), 9));
        assertFalse(NumenToolGateway.referencesIdentity(
                java.util.Set.of(), java.util.Set.of(), "Alice", playerId, 4711));
    }

    @Test
    void playerTargetRefusalIsMachineReadableAndTurkish() {
        JsonObject result = com.google.gson.JsonParser.parseString(
                NumenToolGateway.playerTargetDeniedResult()).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        assertEquals("player_target_denied", result.get("code").getAsString());
        assertTrue(result.get("message").getAsString().contains("Oyunculara saldıramam"));
        assertTrue(result.get("message").getAsString().contains("hostile mob"));
    }

    private static JsonObject argsWithPayload(String payload) {
        JsonObject args = new JsonObject();
        args.addProperty("payload", payload);
        return args;
    }
}
