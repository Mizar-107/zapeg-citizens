package io.github.mizar107.zapegcitizens.brain;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class BrainProtocol {

    static final int VERSION = 1;
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

    private static JsonObject base() {
        JsonObject root = new JsonObject();
        root.addProperty("protocol", VERSION);
        return root;
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
            UUID id, String name, String ownerKind, String ownerId, String role, String faction) {}

    record ActorIdentity(UUID id, String name) {}

    record ToolCall(String id, String name, JsonObject arguments) {}

    record BrainReply(String turnId, ReplyKind kind, String speech, ToolCall toolCall) {}

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
