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

    private static JsonObject argsWithPayload(String payload) {
        JsonObject args = new JsonObject();
        args.addProperty("payload", payload);
        return args;
    }
}
