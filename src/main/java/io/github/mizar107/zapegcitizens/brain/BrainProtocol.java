package io.github.mizar107.zapegcitizens.brain;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.mizar107.zapegcitizens.data.CitizenJobData;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.ActorContext;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobRecord;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.LookTarget;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.PendingAction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

final class BrainProtocol {

    static final int VERSION = 3;
    static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final Gson GSON = new Gson();
    private static final Pattern PROTOCOL_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z0-9_]{1,64}");

    private BrainProtocol() {}

    static String startBody(
            UUID requestId,
            CitizenIdentity citizen,
            ActorIdentity actor,
            String prompt,
            JsonArray tools) {
        JsonObject root = base();
        root.addProperty("request_id", requestId.toString());

        JsonObject citizenJson = new JsonObject();
        citizenJson.addProperty("id", citizen.id().toString());
        citizenJson.addProperty("name", citizen.name());
        citizenJson.addProperty("owner_kind", citizen.ownerKind());
        citizenJson.addProperty("owner_id", citizen.ownerId());
        citizenJson.addProperty("role", citizen.role());
        citizenJson.addProperty("faction", citizen.faction());
        citizenJson.addProperty("persona", citizen.persona());
        citizenJson.addProperty("interaction_mode", citizen.interactionMode());
        root.add("citizen", citizenJson);

        JsonObject actorJson = new JsonObject();
        actorJson.addProperty("id", actor.id().toString());
        actorJson.addProperty("name", actor.name());
        root.add("actor", actorJson);

        root.addProperty("prompt", prompt);
        root.add("tools", tools.deepCopy());
        return GSON.toJson(root);
    }

    static String continueBody(String turnId, String toolCallId, String resultJson) {
        JsonObject root = base();
        root.addProperty("turn_id", turnId);
        root.addProperty("tool_call_id", toolCallId);
        JsonElement result;
        try {
            result = JsonParser.parseString(resultJson);
        } catch (RuntimeException exception) {
            result = GSON.toJsonTree(resultJson);
        }
        root.add("result", result);
        return GSON.toJson(root);
    }

    static String cancelBody(String turnId) {
        JsonObject root = base();
        root.addProperty("turn_id", turnId);
        return GSON.toJson(root);
    }

    static String cancelRequestBody(UUID requestId) {
        JsonObject root = base();
        root.addProperty("request_id", requestId.toString());
        return GSON.toJson(root);
    }

    static String jobStartBody(
            UUID requestId,
            JobRecord job,
            CitizenIdentity citizen,
            JsonArray tools) {
        JsonObject root = base();
        root.addProperty("request_id", requestId.toString());
        root.addProperty("job_id", job.jobId().toString());
        root.add("citizen", citizenJson(citizen));
        root.add("actor", actorContextJson(job));
        root.addProperty("goal", job.goal());
        root.add("tools", tools.deepCopy());
        root.add("budgets", budgetJson(job));
        return GSON.toJson(root);
    }

    static String jobResultBody(
            UUID requestId,
            UUID jobId,
            String actionId,
            String resultJson) {
        JsonObject root = base();
        root.addProperty("request_id", requestId.toString());
        root.addProperty("job_id", jobId.toString());
        root.addProperty("action_id", actionId);
        root.add("result", parseResult(resultJson));
        return GSON.toJson(root);
    }

    static String jobResumeBody(UUID requestId, JobRecord job, String answer) {
        JsonObject root = base();
        root.addProperty("request_id", requestId.toString());
        root.addProperty("job_id", job.jobId().toString());
        if (answer != null && !answer.isBlank()) {
            root.addProperty("answer", answer.strip());
        }
        root.add("checkpoint", checkpointJson(job));
        return GSON.toJson(root);
    }

    static String jobPauseBody(UUID requestId, UUID jobId, String reason) {
        return jobReasonBody(requestId, jobId, reason);
    }

    static String jobCancelBody(UUID requestId, UUID jobId, String reason) {
        return jobReasonBody(requestId, jobId, reason);
    }

    static String jobStatusBody(UUID jobId) {
        JsonObject root = base();
        root.addProperty("job_id", jobId.toString());
        return GSON.toJson(root);
    }

    static String jobListBody(UUID citizenId) {
        JsonObject root = base();
        if (citizenId != null) {
            root.addProperty("citizen_id", citizenId.toString());
        }
        return GSON.toJson(root);
    }

    static BrainReply parseReply(String body) {
        // HTTP responses are byte-bounded before decoding by BrainHttpClient.
        // This secondary character guard protects direct/internal callers too.
        if (body == null || body.length() > MAX_RESPONSE_BYTES) {
            throw new BrainProtocolException("brain response exceeded the size limit");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("brain returned invalid JSON", exception);
        }
        if (requiredInt(root, "protocol") != VERSION) {
            throw new BrainProtocolException("unsupported brain protocol version");
        }
        String turnId = requiredIdentifier(root, "turn_id", PROTOCOL_IDENTIFIER);
        String kindText = requiredString(root, "kind", 32).toUpperCase(Locale.ROOT);
        ReplyKind kind;
        try {
            kind = ReplyKind.valueOf(kindText);
        } catch (IllegalArgumentException exception) {
            throw new BrainProtocolException("unknown brain reply kind: " + kindText, exception);
        }

        if (kind == ReplyKind.FINAL) {
            String speech = requiredSpeech(root, "speech", 2_048);
            if (speech.isEmpty()) {
                speech = "Done.";
            }
            return new BrainReply(turnId, kind, speech, null);
        }

        JsonObject call;
        try {
            call = root.getAsJsonObject("tool_call");
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("tool_call must be an object", exception);
        }
        if (call == null) {
            throw new BrainProtocolException("tool_call is required");
        }
        String id = requiredIdentifier(call, "id", PROTOCOL_IDENTIFIER);
        String name = requiredIdentifier(call, "name", TOOL_NAME);
        JsonObject arguments;
        try {
            arguments = call.has("arguments") && !call.get("arguments").isJsonNull()
                    ? call.getAsJsonObject("arguments")
                    : new JsonObject();
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("tool arguments must be an object", exception);
        }
        return new BrainReply(turnId, kind, null, new ToolCall(id, name, arguments));
    }

    static JobReply parseJobReply(String body) {
        return parseJobReplyObject(parseRoot(body), false);
    }

    /** Status projections may omit a potentially large pending action payload. */
    static JobReply parseJobStatusReply(String body) {
        return parseJobReplyObject(parseRoot(body), true);
    }

    static List<JobReply> parseJobList(String body) {
        JsonObject root = parseRoot(body);
        if (requiredInt(root, "protocol") != VERSION) {
            throw new BrainProtocolException("unsupported brain protocol version");
        }
        JsonArray jobs;
        try {
            jobs = root.getAsJsonArray("jobs");
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("jobs must be an array", exception);
        }
        if (jobs == null || jobs.size() > 1_024) {
            throw new BrainProtocolException("jobs is missing or too large");
        }
        List<JobReply> replies = new ArrayList<>(jobs.size());
        for (JsonElement element : jobs) {
            if (!element.isJsonObject()) {
                throw new BrainProtocolException("every jobs entry must be an object");
            }
            replies.add(parseJobReplyObject(element.getAsJsonObject(), true));
        }
        return List.copyOf(replies);
    }

    static boolean parseHealth(String body) {
        if (body == null || body.length() > MAX_RESPONSE_BYTES) {
            return false;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            return requiredInt(root, "protocol") == VERSION
                    && "ok".equals(requiredString(root, "status", 16));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static String parseError(String body) {
        if (body == null || body.isBlank()) {
            return "empty error response";
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonPrimitive()) {
                return safeErrorText(root.get("error").getAsString());
            }
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject errorObject = root.getAsJsonObject("error");
                if (errorObject.has("message") && errorObject.get("message").isJsonPrimitive()) {
                    return safeErrorText(errorObject.get("message").getAsString());
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to a bounded generic message; never echo an arbitrary HTML body.
        }
        return "brain request was rejected";
    }

    /**
     * Machine-readable error code from a non-2xx brain body
     * ({@code {"error":{"code":"job_in_progress",...}}}), or {@code ""} when
     * absent or unparseable. Callers branch on exact codes (e.g. the healthy
     * still-planning 409) without depending on human-readable message text.
     */
    static String parseErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject errorObject = root.getAsJsonObject("error");
                if (errorObject.has("code") && errorObject.get("code").isJsonPrimitive()) {
                    String code = errorObject.get("code").getAsString().strip();
                    if (PROTOCOL_IDENTIFIER.matcher(code).matches()) {
                        return code;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Unparseable bodies simply have no machine-readable code.
        }
        return "";
    }

    private static JsonObject base() {
        JsonObject root = new JsonObject();
        root.addProperty("protocol", VERSION);
        return root;
    }

    private static JsonObject citizenJson(CitizenIdentity citizen) {
        JsonObject citizenJson = new JsonObject();
        citizenJson.addProperty("id", citizen.id().toString());
        citizenJson.addProperty("name", citizen.name());
        citizenJson.addProperty("owner_kind", citizen.ownerKind());
        citizenJson.addProperty("owner_id", citizen.ownerId());
        citizenJson.addProperty("role", citizen.role());
        citizenJson.addProperty("faction", citizen.faction());
        citizenJson.addProperty("persona", citizen.persona());
        citizenJson.addProperty("interaction_mode", citizen.interactionMode());
        return citizenJson;
    }

    private static JsonObject actorContextJson(JobRecord job) {
        ActorContext context = job.actorContext();
        JsonObject actor = new JsonObject();
        actor.addProperty("id", job.actorId().toString());
        actor.addProperty("name", job.actorName());
        actor.addProperty("dimension", context.dimension());
        actor.addProperty("x", context.x());
        actor.addProperty("y", context.y());
        actor.addProperty("z", context.z());
        actor.addProperty("yaw", context.yaw());
        actor.addProperty("pitch", context.pitch());
        context.lookTarget().ifPresentOrElse(target -> actor.add("look_target", lookTargetJson(target)),
                () -> actor.add("look_target", com.google.gson.JsonNull.INSTANCE));
        return actor;
    }

    private static JsonObject lookTargetJson(LookTarget target) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", target.kind());
        json.addProperty("dimension", target.dimension());
        json.addProperty("x", target.x());
        json.addProperty("y", target.y());
        json.addProperty("z", target.z());
        target.id().ifPresent(value -> json.addProperty("id", value));
        return json;
    }

    private static JsonObject budgetJson(JobRecord job) {
        JsonObject budget = new JsonObject();
        budget.addProperty("max_actions", job.budget().maxActions());
        budget.addProperty("max_model_calls", job.budget().maxModelCalls());
        budget.addProperty("max_active_seconds", job.budget().maxActiveSeconds());
        return budget;
    }

    private static JsonObject checkpointJson(JobRecord job) {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("state", job.state().name());
        checkpoint.addProperty("actions_completed", job.actionsCompleted());
        checkpoint.addProperty("active_seconds", job.activeTicks() / 20L);
        job.lastConfirmedActionId().ifPresent(
                value -> checkpoint.addProperty("last_confirmed_action_id", value));
        Optional<PendingAction> pending = job.pendingAction();
        pending.ifPresent(action -> {
            checkpoint.addProperty("pending_action_id", action.actionId());
            checkpoint.addProperty("pending_action_uncertain", action.uncertain());
        });
        if (pending.isEmpty()) {
            checkpoint.addProperty("pending_action_uncertain", false);
        }
        JsonObject progress = new JsonObject();
        progress.addProperty("phase", job.progress().phase());
        progress.addProperty("summary", job.progress().summary());
        checkpoint.add("progress", progress);
        return checkpoint;
    }

    private static String jobReasonBody(UUID requestId, UUID jobId, String reason) {
        JsonObject root = base();
        root.addProperty("request_id", requestId.toString());
        root.addProperty("job_id", jobId.toString());
        root.addProperty("reason", reason);
        return GSON.toJson(root);
    }

    private static JsonElement parseResult(String resultJson) {
        try {
            return JsonParser.parseString(resultJson);
        } catch (RuntimeException exception) {
            return GSON.toJsonTree(resultJson);
        }
    }

    private static JsonObject parseRoot(String body) {
        if (body == null || body.length() > MAX_RESPONSE_BYTES) {
            throw new BrainProtocolException("brain response exceeded the size limit");
        }
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("brain returned invalid JSON", exception);
        }
    }

    private static JobReply parseJobReplyObject(
            JsonObject root, boolean allowOmittedAction) {
        if (requiredInt(root, "protocol") != VERSION) {
            throw new BrainProtocolException("unsupported brain protocol version");
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(requiredIdentifier(root, "job_id", PROTOCOL_IDENTIFIER));
        } catch (IllegalArgumentException exception) {
            throw new BrainProtocolException("job_id must be a UUID", exception);
        }
        String kindText = requiredString(root, "kind", 32).toUpperCase(Locale.ROOT);
        JobReplyKind kind;
        try {
            kind = JobReplyKind.valueOf(kindText);
        } catch (IllegalArgumentException exception) {
            throw new BrainProtocolException("unknown job reply kind: " + kindText, exception);
        }
        JsonObject progressObject;
        try {
            progressObject = root.getAsJsonObject("progress");
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("progress must be an object", exception);
        }
        if (progressObject == null) {
            throw new BrainProtocolException("progress is required");
        }
        JobProgress progress = new JobProgress(
                requiredSpeech(progressObject, "phase", CitizenJobData.MAX_PHASE_LENGTH),
                requiredSpeech(progressObject, "summary", CitizenJobData.MAX_SUMMARY_LENGTH),
                nonNegativeInt(progressObject, "actions_completed"),
                positiveInt(progressObject, "actions_limit"));
        if (progress.actionsCompleted() > progress.actionsLimit()) {
            throw new BrainProtocolException("progress actions exceed the supplied limit");
        }

        return switch (kind) {
            case ACTION -> {
                Optional<JobAction> action = root.has("action") && !root.get("action").isJsonNull()
                        ? Optional.of(parseJobAction(root))
                        : Optional.empty();
                if (action.isEmpty() && !allowOmittedAction) {
                    throw new BrainProtocolException("action is required");
                }
                yield new JobReply(
                        jobId,
                        kind,
                        progress,
                        action,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
            }
            case NEEDS_INPUT -> new JobReply(
                    jobId,
                    kind,
                    progress,
                    Optional.empty(),
                    Optional.of(requiredSpeech(root, "question", CitizenJobData.MAX_MESSAGE_LENGTH)),
                    Optional.empty(),
                    Optional.empty());
            case COMPLETED -> new JobReply(
                    jobId,
                    kind,
                    progress,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(requiredSpeech(root, "speech", CitizenJobData.MAX_MESSAGE_LENGTH)),
                    Optional.empty());
            case PAUSED -> new JobReply(
                    jobId,
                    kind,
                    progress,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(requiredSpeech(root, "reason", CitizenJobData.MAX_MESSAGE_LENGTH)));
        };
    }

    private static JobAction parseJobAction(JsonObject root) {
        JsonObject action;
        try {
            action = root.getAsJsonObject("action");
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("action must be an object", exception);
        }
        if (action == null) {
            throw new BrainProtocolException("action is required");
        }
        String id = requiredIdentifier(action, "id", PROTOCOL_IDENTIFIER);
        String name = requiredIdentifier(action, "name", TOOL_NAME);
        JsonObject arguments;
        try {
            arguments = action.has("arguments") && !action.get("arguments").isJsonNull()
                    ? action.getAsJsonObject("arguments")
                    : new JsonObject();
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("action arguments must be an object", exception);
        }
        if (arguments.toString().length() > CitizenJobData.MAX_ACTION_ARGUMENTS_LENGTH) {
            throw new BrainProtocolException("action arguments exceeded the size limit");
        }
        return new JobAction(id, name, arguments);
    }

    private static int nonNegativeInt(JsonObject object, String name) {
        int value = requiredInt(object, name);
        if (value < 0) {
            throw new BrainProtocolException(name + " must not be negative");
        }
        return value;
    }

    private static int positiveInt(JsonObject object, String name) {
        int value = requiredInt(object, name);
        if (value < 1) {
            throw new BrainProtocolException(name + " must be positive");
        }
        return value;
    }

    private static int requiredInt(JsonObject object, String name) {
        try {
            JsonElement element = object.get(name);
            if (element == null || !element.isJsonPrimitive()) {
                throw new IllegalArgumentException();
            }
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (!primitive.isNumber()) {
                throw new IllegalArgumentException();
            }
            BigDecimal value = primitive.getAsBigDecimal();
            return value.intValueExact();
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("missing or invalid " + name, exception);
        }
    }

    private static String requiredString(JsonObject object, String name, int maxLength) {
        String value;
        try {
            JsonElement element = object.get(name);
            if (element == null || !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException();
            }
            value = element.getAsString();
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("missing or invalid " + name, exception);
        }
        if (value.isBlank() || value.length() > maxLength) {
            throw new BrainProtocolException(name + " has an invalid length");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new BrainProtocolException(name + " contains a control character");
            }
        }
        return value;
    }

    private static String requiredIdentifier(
            JsonObject object, String name, Pattern pattern) {
        String value = requiredString(object, name, 128);
        if (!pattern.matcher(value).matches()) {
            throw new BrainProtocolException(name + " has an invalid format");
        }
        return value;
    }

    /** Defensive single-line normalization for ordinary multiline model output. */
    private static String requiredSpeech(JsonObject object, String name, int maxLength) {
        String value;
        try {
            JsonElement element = object.get(name);
            if (element == null || !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException();
            }
            value = element.getAsString();
        } catch (RuntimeException exception) {
            throw new BrainProtocolException("missing or invalid " + name, exception);
        }
        if (value.length() > maxLength) {
            throw new BrainProtocolException(name + " has an invalid length");
        }
        StringBuilder normalized = new StringBuilder(value.length());
        boolean previousSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean space = Character.isWhitespace(character)
                    || Character.isISOControl(character);
            if (space) {
                if (!previousSpace && normalized.length() > 0) {
                    normalized.append(' ');
                }
            } else {
                normalized.append(character);
            }
            previousSpace = space;
        }
        return normalized.toString().strip();
    }

    private static String safeErrorText(String raw) {
        StringBuilder safe = new StringBuilder(Math.min(raw.length(), 240));
        for (int index = 0; index < raw.length() && safe.length() < 240; index++) {
            char character = raw.charAt(index);
            safe.append(Character.isISOControl(character) ? ' ' : character);
        }
        String normalized = safe.toString().strip();
        return normalized.isEmpty() ? "brain request was rejected" : normalized;
    }

    record CitizenIdentity(
            UUID id,
            String name,
            String ownerKind,
            String ownerId,
            String role,
            String faction,
            String persona,
            String interactionMode) {}

    record ActorIdentity(UUID id, String name) {}

    record ToolCall(String id, String name, JsonObject arguments) {}

    record BrainReply(String turnId, ReplyKind kind, String speech, ToolCall toolCall) {}

    record JobAction(String id, String name, JsonObject arguments) {}

    record JobProgress(String phase, String summary, int actionsCompleted, int actionsLimit) {}

    record JobReply(
            UUID jobId,
            JobReplyKind kind,
            JobProgress progress,
            Optional<JobAction> action,
            Optional<String> question,
            Optional<String> speech,
            Optional<String> reason) {}

    enum JobReplyKind { ACTION, NEEDS_INPUT, COMPLETED, PAUSED }

    enum ReplyKind { TOOL_CALL, FINAL }

    static final class BrainProtocolException extends RuntimeException {
        BrainProtocolException(String message) {
            super(message);
        }

        BrainProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
