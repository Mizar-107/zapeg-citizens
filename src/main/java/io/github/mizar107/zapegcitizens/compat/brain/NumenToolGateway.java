package io.github.mizar107.zapegcitizens.compat.brain;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Server-side actuator used by ZapeG's shared brain.
 *
 * <p>This is intentionally the only place that reaches into Numen's internal
 * ToolRegistry/task scheduler. Those APIs are not stable yet, so the project
 * pins both Numen jars and keeps the compatibility surface small and testable.
 */
public final class NumenToolGateway {

    public static final String TOOL_CALL_PREFIX = "mcp-zapeg-";
    private static final int MAX_TOOL_CALL_ID_LENGTH = 128;
    private static final int DEFAULT_ARGUMENTS_JSON_BYTES = 16 * 1024;
    private static final int BUILD_ARGUMENTS_JSON_BYTES = 256 * 1024;

    private static final Gson GSON = new Gson();
    private static final PendingToolResults RESULTS = new PendingToolResults();

    private NumenToolGateway() {}

    /**
     * Fail server startup if the exact pinned tool surface or required result-hook
     * target is absent. Loading TaskQueue also makes required-Mixin failure visible
     * before the first real citizen task.
     */
    public static int verifyCompatibility() {
        for (String name : WorkerToolPolicy.orderedNames()) {
            if (ToolRegistry.get(name) == null) {
                throw new IllegalStateException(
                        "Pinned Numen is missing required worker tool: " + name);
            }
        }
        try {
            Class.forName(
                    "com.dwinovo.numen.task.TaskQueue",
                    true,
                    NumenToolGateway.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Pinned Numen TaskQueue is unavailable", exception);
        }
        io.github.mizar107.zapegcitizens.skill.JobSkillCatalog.verifyResources();
        return WorkerToolPolicy.orderedNames().size() + 1;
    }

    /** OpenAI/Ollama-compatible function-tool definitions in stable policy order. */
    public static JsonArray toolDefinitions() {
        JsonArray definitions = new JsonArray();
        for (String name : WorkerToolPolicy.orderedNames()) {
            NumenTool tool = ToolRegistry.get(name);
            if (tool == null) {
                // Fail closed if the pinned Numen surface ever changes. execute()
                // applies the same lookup and will reject an unavailable tool.
                continue;
            }
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty(
                    "description", NumenToolSurface.description(tool.name(), tool.description()));
            function.add("parameters", GSON.toJsonTree(tool.parameterSchema()));

            JsonObject definition = new JsonObject();
            definition.addProperty("type", "function");
            definition.add("function", function);
            definitions.add(definition);
        }
        definitions.add(NumenSkillGateway.toolDefinition());
        definitions.add(ZapegWorkerToolGateway.toolDefinition());
        return definitions;
    }

    /**
     * Execute one exact, allowlisted Numen tool against a managed body.
     *
     * <p>The caller may be on an HTTP worker thread. The tool itself always runs
     * on the Minecraft server thread. The result sink runs at most once: query
     * results and validation failures arrive immediately; queued actions arrive
     * from {@code TaskQueue.complete} through the mixin hook.
     */
    public static void execute(
            NumenPlayer citizen,
            String toolCallId,
            String toolName,
            JsonObject args,
            Consumer<String> result) {
        Objects.requireNonNull(result, "result");
        if (citizen == null) {
            result.accept(failure("citizen is required"));
            return;
        }
        if (!isValidToolCallId(toolCallId)) {
            result.accept(failure(
                    "tool call id must start with " + TOOL_CALL_PREFIX + " and fit within 128 characters"));
            return;
        }
        if (!WorkerToolPolicy.isAllowed(toolName)
                && !NumenSkillGateway.handles(toolName)
                && !ZapegWorkerToolGateway.handles(toolName)) {
            result.accept(failure("tool is not allowed for worker citizens: " + toolName));
            return;
        }
        JsonObject safeArgs = args == null ? new JsonObject() : args.deepCopy();
        int argumentBytes = argumentSizeBytes(safeArgs);
        int argumentLimit = argumentLimitFor(toolName);
        if (argumentBytes > argumentLimit) {
            result.accept(failure("tool arguments exceed the " + argumentLimit
                    + " UTF-8 byte limit for " + toolName));
            return;
        }
        if (!RESULTS.register(toolCallId, citizen.getUUID(), result)) {
            result.accept(failure("duplicate tool call id: " + toolCallId));
            return;
        }

        MinecraftServer server = citizen.level().getServer();
        Runnable invoke = () -> invokeOnServerThread(
                citizen, toolCallId, toolName, safeArgs);
        if (server.isSameThread()) {
            invoke.run();
        } else {
            server.execute(invoke);
        }
    }

    public static boolean cancelExecution(String toolCallId) {
        return RESULTS.cancel(toolCallId);
    }

    /** Cancel the body's Numen task lane and forget all callbacks addressed to it. */
    public static void cancelBody(NumenPlayer citizen) {
        if (citizen == null) {
            return;
        }
        RESULTS.cancelBody(citizen.getUUID());
        MinecraftServer server = citizen.level().getServer();
        Runnable cancel = () -> CompanionTickDispatcher.cancelFor(citizen);
        if (server.isSameThread()) {
            cancel.run();
        } else {
            server.execute(cancel);
        }
    }

    /** Called only by the pinned-Numen TaskQueue mixin. */
    public static void onTaskCompleted(TaskRecord record) {
        if (record == null || record.getToolCallId() == null
                || !record.getToolCallId().startsWith(TOOL_CALL_PREFIX)) {
            return;
        }
        TaskResult result = record.getResult();
        String resultJson = result == null
                ? failure("Numen task completed without a result")
                : result.toJson();
        RESULTS.acceptTerminal(
                record.getToolCallId(), NumenToolSurface.result(resultJson));
    }

    private static void invokeOnServerThread(
            NumenPlayer citizen, String toolCallId, String toolName, JsonObject args) {
        if (citizen.isRemoved()) {
            RESULTS.acceptTerminal(toolCallId, failure("citizen body is no longer live"));
            return;
        }
        if (NumenSkillGateway.handles(toolName)) {
            try {
                RESULTS.acceptImmediate(toolCallId, NumenSkillGateway.execute(args));
            } catch (RuntimeException exception) {
                String message = exception.getMessage();
                RESULTS.acceptTerminal(toolCallId, failure(
                        "invalid tool call: "
                                + (message == null
                                        ? exception.getClass().getSimpleName()
                                        : message)));
            }
            return;
        }
        if (ZapegWorkerToolGateway.handles(toolName)) {
            try {
                RESULTS.acceptImmediate(
                        toolCallId, ZapegWorkerToolGateway.execute(citizen, args));
            } catch (RuntimeException exception) {
                String message = exception.getMessage();
                RESULTS.acceptTerminal(toolCallId, failure(
                        "invalid tool call: "
                                + (message == null
                                        ? exception.getClass().getSimpleName()
                                        : message)));
            }
            return;
        }
        NumenTool tool = ToolRegistry.get(toolName);
        if (tool == null) {
            RESULTS.acceptTerminal(
                    toolCallId,
                    failure("allowed tool is unavailable in pinned Numen: " + toolName));
            return;
        }
        try {
            tool.onServerCall(
                    toolCallId,
                    args,
                    citizen,
                    json -> RESULTS.acceptImmediate(
                            toolCallId, NumenToolSurface.result(json)));
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            RESULTS.acceptTerminal(toolCallId, failure(
                    "invalid tool call: " + (message == null ? exception.getClass().getSimpleName() : message)));
        }
    }

    private static String failure(String message) {
        return TaskResult.fail(message).toJson();
    }

    static int argumentLimitFor(String toolName) {
        return "build".equals(toolName)
                ? BUILD_ARGUMENTS_JSON_BYTES
                : DEFAULT_ARGUMENTS_JSON_BYTES;
    }

    static int argumentSizeBytes(JsonObject args) {
        JsonObject safe = args == null ? new JsonObject() : args;
        return safe.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    static boolean argumentsFit(String toolName, JsonObject args) {
        return argumentSizeBytes(args) <= argumentLimitFor(toolName);
    }

    static boolean isValidToolCallId(String toolCallId) {
        return toolCallId != null
                && toolCallId.startsWith(TOOL_CALL_PREFIX)
                && toolCallId.length() > TOOL_CALL_PREFIX.length()
                && toolCallId.length() <= MAX_TOOL_CALL_ID_LENGTH;
    }
}
