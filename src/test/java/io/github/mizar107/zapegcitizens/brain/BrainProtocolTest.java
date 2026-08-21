package io.github.mizar107.zapegcitizens.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.ActorContext;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobBudget;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobProgress;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobRecord;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobState;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.LookTarget;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.PendingAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                        citizenId,
                        "Atlas",
                        "PLAYER",
                        actorId.toString(),
                        "worker",
                        "players",
                        "A patient village blacksmith.\nSpeaks plainly.",
                        "TASK"),
                new BrainProtocol.ActorIdentity(actorId, "Alice"),
                "collect iron",
                tools)).getAsJsonObject();

        assertEquals(3, body.get("protocol").getAsInt());
        assertEquals(citizenId.toString(), body.getAsJsonObject("citizen").get("id").getAsString());
        assertEquals("A patient village blacksmith.\nSpeaks plainly.",
                body.getAsJsonObject("citizen").get("persona").getAsString());
        assertEquals("TASK",
                body.getAsJsonObject("citizen").get("interaction_mode").getAsString());
        assertEquals("collect iron", body.get("prompt").getAsString());
        assertEquals(1, body.getAsJsonArray("tools").size());
        assertEquals(false, body.has("token"));
        assertEquals(false, body.has("api_key"));
    }

    @Test
    void parsesFinalAndToolReplies() {
        BrainProtocol.BrainReply finished = BrainProtocol.parseReply("""
                {"protocol":3,"turn_id":"turn-1","kind":"final","speech":"Done"}
                """);
        assertEquals(BrainProtocol.ReplyKind.FINAL, finished.kind());
        assertEquals("Done", finished.speech());

        BrainProtocol.BrainReply tool = BrainProtocol.parseReply("""
                {"protocol":3,"turn_id":"turn-2","kind":"tool_call",
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
        assertEquals(true, BrainProtocol.parseHealth("{\"protocol\":3,\"status\":\"ok\"}"));
        assertEquals(false, BrainProtocol.parseHealth("{\"protocol\":1,\"status\":\"ok\"}"));
    }

    @Test
    void rejectsUnsupportedOrMalformedReplies() {
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":1,\"turn_id\":\"x\",\"kind\":\"final\",\"speech\":\"ok\"}"));
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":3,\"turn_id\":\"x\",\"kind\":\"tool_call\"}"));
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply("not-json"));
        assertEquals("turn is canceled", BrainProtocol.parseError(
                "{\"protocol\":3,\"error\":{\"code\":\"turn_not_active\","
                        + "\"message\":\"turn is canceled\"}}"));
    }

    @Test
    void extractsMachineReadableErrorCodesForStillPlanningClassification() {
        assertEquals("job_in_progress", BrainProtocol.parseErrorCode(
                "{\"protocol\":3,\"error\":{\"code\":\"job_in_progress\","
                        + "\"message\":\"job operation is still waiting for the model\"}}"));
        assertEquals("job_not_ready", BrainProtocol.parseErrorCode(
                "{\"protocol\":3,\"error\":{\"code\":\"job_not_ready\","
                        + "\"message\":\"job is canceled\"}}"));

        // Missing, malformed, or unsafe codes yield the empty sentinel.
        assertEquals("", BrainProtocol.parseErrorCode(
                "{\"protocol\":3,\"error\":{\"message\":\"no code here\"}}"));
        assertEquals("", BrainProtocol.parseErrorCode(
                "{\"protocol\":3,\"error\":\"plain text\"}"));
        assertEquals("", BrainProtocol.parseErrorCode("not-json"));
        assertEquals("", BrainProtocol.parseErrorCode(null));
        assertEquals("", BrainProtocol.parseErrorCode(
                "{\"error\":{\"code\":\"has spaces and \\n control\"}}"));
    }

    @Test
    void rejectsCoercedTypesAndControlCharacters() {
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":\"2\",\"turn_id\":\"turn-1\","
                                + "\"kind\":\"final\",\"speech\":\"ok\"}"));
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":3,\"turn_id\":7,\"kind\":\"final\","
                                + "\"speech\":\"ok\"}"));
        BrainProtocol.BrainReply multiline = BrainProtocol.parseReply(
                "{\"protocol\":3,\"turn_id\":\"turn-1\",\"kind\":\"final\","
                        + "\"speech\":\"first line\\n\\tsecond line\"}");
        assertEquals("first line second line", multiline.speech());
        assertThrows(BrainProtocol.BrainProtocolException.class,
                () -> BrainProtocol.parseReply(
                        "{\"protocol\":3,\"turn_id\":\"turn-1\",\"kind\":\"tool_call\","
                                + "\"tool_call\":{\"id\":\"call-1\",\"name\":\"mine\\nforged\","
                                + "\"arguments\":{}}}"));
        assertEquals("bad header", BrainProtocol.parseError(
                "{\"error\":{\"message\":\"bad\\nheader\"}}"));
    }

    @Test
    void buildsGroundedDurableJobRequests() {
        JobRecord job = job();
        BrainProtocol.CitizenIdentity identity = new BrainProtocol.CitizenIdentity(
                job.citizenId(),
                "Atlas",
                "SERVER",
                "world",
                "worker",
                "village",
                "Patient builder",
                "TASK");
        JsonObject start = JsonParser.parseString(BrainProtocol.jobStartBody(
                job.startRequestId(), job, identity, new JsonArray())).getAsJsonObject();

        assertEquals(3, start.get("protocol").getAsInt());
        assertEquals(job.jobId().toString(), start.get("job_id").getAsString());
        assertEquals("minecraft:overworld",
                start.getAsJsonObject("actor").get("dimension").getAsString());
        assertEquals("BLOCK", start.getAsJsonObject("actor")
                .getAsJsonObject("look_target").get("kind").getAsString());
        assertEquals(128, start.getAsJsonObject("budgets").get("max_actions").getAsInt());

        JsonObject resume = JsonParser.parseString(BrainProtocol.jobResumeBody(
                UUID.randomUUID(), job, "use spruce")).getAsJsonObject();
        assertEquals("use spruce", resume.get("answer").getAsString());
        assertEquals(true, resume.getAsJsonObject("checkpoint")
                .get("pending_action_uncertain").getAsBoolean());

        JsonObject result = JsonParser.parseString(BrainProtocol.jobResultBody(
                UUID.randomUUID(), job.jobId(), "act-2", "{\"success\":true}"))
                .getAsJsonObject();
        assertEquals(true, result.getAsJsonObject("result").get("success").getAsBoolean());
    }

    @Test
    void parsesEveryDurableJobReplyAndList() {
        UUID jobId = UUID.randomUUID();
        String progress = "\"progress\":{\"phase\":\"survey\","
                + "\"summary\":\"Checking the site\","
                + "\"actions_completed\":2,\"actions_limit\":128}";
        BrainProtocol.JobReply action = BrainProtocol.parseJobReply(
                "{\"protocol\":3,\"job_id\":\"" + jobId + "\","
                        + "\"kind\":\"ACTION\"," + progress + ","
                        + "\"action\":{\"id\":\"act-3\",\"name\":\"scan_blocks\","
                        + "\"arguments\":{\"radius\":12}}}");
        assertEquals(BrainProtocol.JobReplyKind.ACTION, action.kind());
        assertEquals("scan_blocks", action.action().orElseThrow().name());

        BrainProtocol.JobReply needsInput = BrainProtocol.parseJobReply(
                "{\"protocol\":3,\"job_id\":\"" + jobId + "\","
                        + "\"kind\":\"NEEDS_INPUT\"," + progress + ","
                        + "\"question\":\"Which wood?\"}");
        assertEquals("Which wood?", needsInput.question().orElseThrow());

        BrainProtocol.JobReply completed = BrainProtocol.parseJobReply(
                "{\"protocol\":3,\"job_id\":\"" + jobId + "\","
                        + "\"kind\":\"COMPLETED\"," + progress + ","
                        + "\"speech\":\"Finished.\"}");
        assertEquals("Finished.", completed.speech().orElseThrow());

        String pausedJson = "{\"protocol\":3,\"job_id\":\"" + jobId + "\","
                + "\"kind\":\"PAUSED\"," + progress + ","
                + "\"reason\":\"Need materials\"}";
        BrainProtocol.JobReply paused = BrainProtocol.parseJobReply(pausedJson);
        assertEquals("Need materials", paused.reason().orElseThrow());

        List<BrainProtocol.JobReply> listed = BrainProtocol.parseJobList(
                "{\"protocol\":3,\"jobs\":[" + pausedJson + "]}");
        assertEquals(List.of(jobId), listed.stream().map(BrainProtocol.JobReply::jobId).toList());

        BrainProtocol.JobReply projected = BrainProtocol.parseJobStatusReply(
                "{\"protocol\":3,\"job_id\":\"" + jobId + "\","
                        + "\"kind\":\"ACTION\"," + progress + "}");
        assertTrue(projected.action().isEmpty());
        assertThrows(BrainProtocol.BrainProtocolException.class, () ->
                BrainProtocol.parseJobReply(
                        "{\"protocol\":3,\"job_id\":\"" + jobId + "\","
                                + "\"kind\":\"ACTION\"," + progress + "}"));
    }

    @Test
    void rejectsJobActionsWithInconsistentOrInvalidProgress() {
        UUID jobId = UUID.randomUUID();
        assertThrows(BrainProtocol.BrainProtocolException.class, () ->
                BrainProtocol.parseJobReply(
                        "{\"protocol\":3,\"job_id\":\"" + jobId + "\","
                                + "\"kind\":\"ACTION\","
                                + "\"progress\":{\"phase\":\"x\",\"summary\":\"x\","
                                + "\"actions_completed\":3,\"actions_limit\":2},"
                                + "\"action\":{\"id\":\"a\",\"name\":\"mine\","
                                + "\"arguments\":{}}}"));
    }

    private static JobRecord job() {
        UUID jobId = UUID.randomUUID();
        return new JobRecord(
                jobId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Alice",
                "Build a villa here",
                new ActorContext(
                        "minecraft:overworld",
                        10.5,
                        64,
                        -4.5,
                        90,
                        0,
                        Optional.of(new LookTarget(
                                "BLOCK",
                                "minecraft:overworld",
                                14,
                                63,
                                -4,
                                Optional.of("up")))),
                new JobBudget(128, 192, 10_800),
                JobState.PAUSED,
                new JobProgress("survey", "Checking the site"),
                2,
                400,
                Optional.of(new PendingAction(
                        "act-2",
                        "mcp-zapeg-job-" + jobId + "-2",
                        "scan_blocks",
                        "{\"radius\":12}",
                        true,
                        true,
                        Optional.empty())),
                Optional.of("act-1"),
                Optional.of("server restarted"),
                100,
                200);
    }
}
