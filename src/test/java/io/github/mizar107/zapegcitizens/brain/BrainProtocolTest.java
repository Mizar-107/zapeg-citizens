package io.github.mizar107.zapegcitizens.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrainProtocolTest {

    @Test
    void buildsStartRequestWithoutAnyCredentialField() {
        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tools.add(tool);
        UUID citizenId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        JsonObject body = JsonParser.parseString(BrainProtocol.startBody(
                UUID.randomUUID(),
                new BrainProtocol.CitizenIdentity(
                        citizenId, "Atlas", "PLAYER", actorId.toString(), "worker", "players"),
                new BrainProtocol.ActorIdentity(actorId, "Alice"),
                "collect iron",
                tools)).getAsJsonObject();

        assertEquals(1, body.get("protocol").getAsInt());
        assertEquals(citizenId.toString(), body.getAsJsonObject("citizen").get("id").getAsString());
        assertEquals("collect iron", body.get("prompt").getAsString());
        assertEquals(1, body.getAsJsonArray("tools").size());
        assertEquals(false, body.has("token"));
        assertEquals(false, body.has("api_key"));
    }

    @Test
    void parsesFinalAndToolReplies() {
        BrainProtocol.BrainReply finished = BrainProtocol.parseReply("""
                {"protocol":1,"turn_id":"turn-1","kind":"final","speech":"Done"}
                """);
        assertEquals(BrainProtocol.ReplyKind.FINAL, finished.kind());
        assertEquals("Done", finished.speech());

        BrainProtocol.BrainReply tool = BrainProtocol.parseReply("""
                {"protocol":1,"turn_id":"turn-2","kind":"tool_call",
                 "tool_call":{"id":"call-1","name":"scan_blocks","arguments":{"radius":8}}}
                """);
        assertEquals(BrainProtocol.ReplyKind.TOOL_CALL, tool.kind());
        assertEquals("scan_blocks", tool.toolCall().name());
        assertEquals(8, tool.toolCall().arguments().get("radius").getAsInt());
    }

    @Test
    void keepsStructuredToolResultsStructured() {
        JsonObject body = JsonParser.parseString(
                BrainProtocol.continueBody("turn-1", "call-1",
                        "{\"success\":true,\"data\":{\"count\":3}}"))
                .getAsJsonObject();

        assertEquals(true, body.getAsJsonObject("result").get("success").getAsBoolean());
        assertEquals(3, body.getAsJsonObject("result")
                .getAsJsonObject("data").get("count").getAsInt());

        UUID requestId = UUID.randomUUID();
        JsonObject cancel = JsonParser.parseString(
                BrainProtocol.cancelRequestBody(requestId)).getAsJsonObject();
        assertEquals(requestId.toString(), cancel.get("request_id").getAsString());
    }

    @Test
    void rejectsUnsupportedOrMalformedReplies() {
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":2,\"turn_id\":\"x\",\"kind\":\"final\",\"speech\":\"ok\"}"));
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":1,\"turn_id\":\"x\",\"kind\":\"tool_call\"}"));
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply("not-json"));
        assertEquals("turn is canceled", BrainProtocol.parseError(
                "{\"protocol\":1,\"error\":{\"code\":\"turn_not_active\","
                        + "\"message\":\"turn is canceled\"}}"));
    }

    @Test
    void rejectsCoercedTypesAndControlCharacters() {
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":\"1\",\"turn_id\":\"turn-1\","
                                + "\"kind\":\"final\",\"speech\":\"ok\"}"));
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":1,\"turn_id\":7,\"kind\":\"final\","
                                + "\"speech\":\"ok\"}"));
        BrainProtocol.BrainReply multiline = BrainProtocol.parseReply(
                "{\"protocol\":1,\"turn_id\":\"turn-1\",\"kind\":\"final\","
                        + "\"speech\":\"first line\\n\\tsecond line\"}");
        assertEquals("first line second line", multiline.speech());
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":1,\"turn_id\":\"turn-1\",\"kind\":\"tool_call\","
                                + "\"tool_call\":{\"id\":\"call-1\",\"name\":\"mine\\nforged\","
                                + "\"arguments\":{}}}"));
        assertEquals("bad header", BrainProtocol.parseError(
                "{\"error\":{\"message\":\"bad\\nheader\"}}"));
    }
}
