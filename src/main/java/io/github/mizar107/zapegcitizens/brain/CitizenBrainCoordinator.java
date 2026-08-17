package io.github.mizar107.zapegcitizens.brain;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import io.github.mizar107.zapegcitizens.ZapeGCitizens;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.ActorIdentity;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.BrainReply;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.CitizenIdentity;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.ReplyKind;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.ToolCall;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat;
import io.github.mizar107.zapegcitizens.compat.brain.NumenToolGateway;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runs one bounded server-side agent turn per managed citizen. HTTP never blocks
 * the Minecraft thread; every world/tool transition is marshalled back to it.
 */
public final class CitizenBrainCoordinator {

    private static final CitizenBrainCoordinator INSTANCE = new CitizenBrainCoordinator();

    private final Map<UUID, ActiveTurn> active = new ConcurrentHashMap<>();
    private final Optional<BrainConfig> config;
    private final BrainHttpClient http;

    private CitizenBrainCoordinator() {
        config = BrainConfig.fromEnvironment();
        http = config.map(BrainHttpClient::new).orElse(null);
    }

    public static CitizenBrainCoordinator instance() {
        return INSTANCE;
    }

    public void logConfiguration() {
        if (config.isPresent()) {
            ZapeGCitizens.LOGGER.info(
                    "Shared citizen brain enabled at {} (max tool steps {}, turn timeout {} ms)",
                    config.orElseThrow().baseUri(),
                    config.orElseThrow().maxToolSteps(),
                    config.orElseThrow().turnTimeout().toMillis());
        } else {
            ZapeGCitizens.LOGGER.warn(
                    "Shared citizen brain disabled; set CITIZENS_BRAIN_URL and a brain token");
        }
    }

    public boolean isEnabled() {
        return config.isPresent();
    }

    public int activeCount() {
        return active.size();
    }

    public String statusSummary() {
        return http == null
                ? "Shared brain: disabled (set CITIZENS_BRAIN_URL and token)."
                : "Shared brain: configured; " + active.size() + " active dialogue turn(s), "
                        + CitizenJobManager.instance().inFlightCount()
                        + " job request(s) in flight.";
    }

    public void health(ServerPlayer requester) {
        if (http == null) {
            tell(requester, "[Citizens] Shared brain is disabled on this server.");
            return;
        }
        http.health().thenAccept(healthy -> requester.server.execute(() -> tell(requester,
                healthy
                        ? "[Citizens] Shared brain is healthy (" + active.size() + " active turn(s))."
                        : "[Citizens] Shared brain did not pass its health check.")));
    }

    public boolean submit(
            ServerPlayer actor,
            CitizenRegistryData.CitizenRecord record,
            NumenPlayer citizen,
            String prompt) {
        return submit(actor, record, citizen, prompt, InteractionMode.TASK);
    }

    public boolean submit(
            ServerPlayer actor,
            CitizenRegistryData.CitizenRecord record,
            NumenPlayer citizen,
            String prompt,
            InteractionMode mode) {
        if (mode == InteractionMode.TASK) {
            return CitizenJobManager.instance()
                    .submit(actor, record, citizen, prompt)
                    .successful();
        }
        if (http == null) {
            tell(actor, "[Citizens] The shared brain is not configured. Ask the server host to start it.");
            return false;
        }
        if (prompt == null || prompt.isBlank() || prompt.length() > 256) {
            tell(actor, "[Citizens] Tasks must contain 1-256 characters.");
            return false;
        }

        ActiveTurn turn = new ActiveTurn(
                UUID.randomUUID(),
                actor.server,
                actor.getUUID(),
                actor.getGameProfile().getName(),
                record,
                citizen.getUUID(),
                mode,
                System.nanoTime());
        if (active.putIfAbsent(citizen.getUUID(), turn) != null) {
            tell(actor, mode == InteractionMode.DIALOGUE
                    ? "[Citizens] " + record.name()
                            + " is already speaking with someone. Please wait."
                    : record.logicalOwner().kind() == CitizenRegistryData.OwnerKind.SERVER
                            ? "[Citizens] " + record.name()
                                    + " is already handling a task. An operator can use "
                                    + "/citizen stop " + record.name() + "."
                            : "[Citizens] " + record.name()
                                    + " is already handling a task. Use @"
                                    + record.name() + " stop first.");
            return false;
        }

        ZapeGCitizens.LOGGER.info(
                "[citizen-audit] accepted request={} actor={} citizen={} mode={} prompt_chars={}",
                turn.requestId, turn.actorId, turn.citizenId, mode, prompt.length());
        if (mode == InteractionMode.TASK) {
            tell(actor, "[Citizens] Task accepted for " + record.name() + ".");
        }

        CitizenIdentity identity = new CitizenIdentity(
                record.citizenId(),
                record.name(),
                record.logicalOwner().kind().name(),
                record.logicalOwner().id(),
                record.role(),
                record.faction(),
                record.persona(),
                mode.name());
        JsonArray tools = mode == InteractionMode.TASK
                ? NumenToolGateway.toolDefinitions()
                : new JsonArray();
        awaitHttp(turn, () -> http.start(
                turn.requestId,
                identity,
                new ActorIdentity(turn.actorId, turn.actorName),
                prompt.strip(),
                tools));
        return true;
    }

    public boolean stop(
            ServerPlayer actor,
            CitizenRegistryData.CitizenRecord record,
            NumenPlayer citizen) {
        CitizenJobManager.JobOperation jobStop = CitizenJobManager.instance().cancel(
                actor.server, record, "canceled by " + actor.getGameProfile().getName());
        ActiveTurn turn = active.remove(record.citizenId());
        if (turn != null) {
            turn.cancelled = true;
            if (turn.executionId != null) {
                NumenToolGateway.cancelExecution(turn.executionId);
            }
            if (http != null) {
                cancelRemote(turn);
            }
        }
        tell(actor, "[Citizens] Stopped " + record.name() + " and cleared its current work.");
        ZapeGCitizens.LOGGER.info(
                "[citizen-audit] stopped actor={} citizen={} had_turn={} had_job={}",
                actor.getUUID(), record.citizenId(), turn != null, jobStop.successful());
        return turn != null || jobStop.successful();
    }

    public void stopForLogout(MinecraftServer server, UUID ownerId) {
        CitizenJobManager.instance().ownerUnavailable(server, ownerId);
        for (ActiveTurn turn : active.values()) {
            if (turn.actorId.equals(ownerId)
                    || turn.record.logicalOwner().matchesPlayer(ownerId)) {
                cancelWithoutMessage(turn, "owner logged out");
            }
        }
    }

    /** Resolve a turn when Numen removes or kills its body without producing a task result. */
    public void bodyUnavailable(NumenPlayer citizen, String reason) {
        CitizenJobManager.instance().bodyUnavailable(citizen, reason);
        ActiveTurn turn = active.remove(citizen.getUUID());
        if (turn == null) {
            return;
        }
        turn.cancelled = true;
        if (turn.executionId != null) {
            NumenToolGateway.cancelExecution(turn.executionId);
        }
        if (http != null) {
            cancelRemote(turn);
        }
        tellActor(turn, "[Citizens] " + turn.record.name() + " stopped: " + reason + ".");
        ZapeGCitizens.LOGGER.info(
                "[citizen-audit] body unavailable request={} citizen={} reason={}",
                turn.requestId, turn.citizenId, reason);
    }

    /** Cancel any logical/physical work before an operator permanently removes a citizen. */
    public boolean stopForRemoval(
            MinecraftServer server, CitizenRegistryData.CitizenRecord record) {
        UUID citizenId = record.citizenId();
        ActiveTurn turn = active.get(citizenId);
        if (turn != null) {
            cancelWithoutMessage(turn, "operator requested removal");
            tellActor(turn, "[Citizens] " + turn.record.name()
                    + " was stopped by an operator.");
        }
        return turn != null;
    }

    /**
     * Fail closed on a corrupt owner mapping without touching the mismatched
     * Numen body's physical task lane.
     */
    public void stopForOwnershipMismatch(UUID citizenId) {
        ActiveTurn turn = active.remove(citizenId);
        if (turn == null) {
            return;
        }
        turn.cancelled = true;
        if (turn.executionId != null) {
            NumenToolGateway.cancelExecution(turn.executionId);
        }
        if (http != null) {
            cancelRemote(turn);
        }
        tellActor(turn, "[Citizens] " + turn.record.name()
                + " was disabled because its body-owner identity is inconsistent.");
        ZapeGCitizens.LOGGER.warn(
                "[citizen-audit] owner mismatch request={} citizen={}",
                turn.requestId, turn.citizenId);
    }

    /** Server-tick watchdog for lost Numen callbacks or otherwise hung agent turns. */
    public void expireTimedOutTurns(MinecraftServer server) {
        CitizenJobManager.instance().tick(server);
        if (config.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        long timeoutNanos = config.orElseThrow().turnTimeout().toNanos();
        for (ActiveTurn turn : active.values()) {
            if (turn.server == server && now - turn.startedNanos >= timeoutNanos) {
                fail(turn, "the task exceeded its overall time limit");
            }
        }
    }

    public void shutdown(MinecraftServer server) {
        CitizenJobManager.instance().shutdown(server);
        for (ActiveTurn turn : active.values()) {
            cancelWithoutMessage(turn, "server stopping");
        }
        active.clear();
    }

    private void onHttpCompletion(ActiveTurn turn, BrainReply reply, Throwable error) {
        turn.server.execute(() -> {
            if (!isCurrent(turn)) {
                // Stop/logout can win while /start is in flight. If its late
                // response created a turn, close that exact turn rather than
                // leaving it to block the citizen until sidecar TTL expiry.
                if (reply != null && http != null) {
                    observeRemoteCancel(turn, http.cancel(reply.turnId()));
                }
                return;
            }
            if (error != null) {
                fail(turn, friendlyError(error));
                return;
            }
            handleReply(turn, reply);
        });
    }

    private void handleReply(ActiveTurn turn, BrainReply reply) {
        if (reply == null) {
            fail(turn, "the brain returned no response");
            return;
        }
        if (turn.turnId == null) {
            turn.turnId = reply.turnId();
        } else if (!turn.turnId.equals(reply.turnId())) {
            fail(turn, "the brain changed the turn identifier");
            return;
        }

        if (reply.kind() == ReplyKind.FINAL) {
            active.remove(turn.citizenId, turn);
            ZapeGCitizens.LOGGER.info(
                    "[citizen-audit] completed request={} citizen={} tool_steps={}",
                    turn.requestId, turn.citizenId, turn.toolSteps);
            String speech = "[" + turn.record.name() + "] " + reply.speech();
            if (turn.mode == InteractionMode.DIALOGUE) {
                turn.server.getPlayerList().broadcastSystemMessage(Component.literal(speech), false);
            } else {
                tellActor(turn, speech);
            }
            return;
        }

        if (turn.mode == InteractionMode.DIALOGUE) {
            fail(turn, "dialogue mode cannot execute world tools");
            return;
        }

        if (++turn.toolSteps > config.orElseThrow().maxToolSteps()) {
            fail(turn, "the task exceeded its tool-step limit");
            return;
        }
        ToolCall call = reply.toolCall();
        if (call == null) {
            fail(turn, "the brain omitted its tool call");
            return;
        }
        NumenPlayer citizen = NumenServerCompat.findLiveManaged(
                turn.server, turn.citizenId, turn.record.bodyOwnerId());
        if (citizen == null) {
            fail(turn, "the citizen is no longer live");
            return;
        }

        String executionId = "mcp-zapeg-" + turn.requestId + "-" + turn.toolSteps;
        turn.executionId = executionId;
        turn.providerToolCallId = call.id();
        ZapeGCitizens.LOGGER.info(
                "[citizen-audit] tool request={} citizen={} step={} tool={}",
                turn.requestId, turn.citizenId, turn.toolSteps, call.name());
        NumenToolGateway.execute(citizen, executionId, call.name(), call.arguments(),
                result -> onToolResult(turn, executionId, call.id(), result));
    }

    private void onToolResult(
            ActiveTurn turn, String executionId, String providerToolCallId, String result) {
        turn.server.execute(() -> {
            if (!isCurrent(turn) || !executionId.equals(turn.executionId)) {
                return;
            }
            turn.executionId = null;
            turn.providerToolCallId = null;
            awaitHttp(turn, () -> http.continueTurn(
                    turn.turnId, providerToolCallId, result));
        });
    }

    /** Convert even pre-future request-construction failures into normal turn failures. */
    private void awaitHttp(
            ActiveTurn turn, Supplier<CompletableFuture<BrainReply>> operation) {
        try {
            operation.get().whenComplete((reply, error) -> onHttpCompletion(turn, reply, error));
        } catch (RuntimeException error) {
            onHttpCompletion(turn, null, error);
        }
    }

    private void fail(ActiveTurn turn, String reason) {
        if (!active.remove(turn.citizenId, turn)) {
            return;
        }
        turn.cancelled = true;
        if (turn.executionId != null) {
            NumenToolGateway.cancelExecution(turn.executionId);
        }
        if (http != null) {
            cancelRemote(turn);
        }
        tellActor(turn, "[Citizens] " + turn.record.name() + " stopped: " + reason + ".");
        ZapeGCitizens.LOGGER.warn(
                "[citizen-audit] failed request={} citizen={} reason={}",
                turn.requestId, turn.citizenId, reason);
    }

    private void cancelWithoutMessage(ActiveTurn turn, String reason) {
        if (!active.remove(turn.citizenId, turn)) {
            return;
        }
        turn.cancelled = true;
        if (turn.executionId != null) {
            NumenToolGateway.cancelExecution(turn.executionId);
        }
        if (http != null) {
            cancelRemote(turn);
        }
        ZapeGCitizens.LOGGER.info(
                "[citizen-audit] cancelled request={} citizen={} reason={}",
                turn.requestId, turn.citizenId, reason);
    }

    private boolean isCurrent(ActiveTurn turn) {
        return !turn.cancelled && active.get(turn.citizenId) == turn;
    }

    private void cancelRemote(ActiveTurn turn) {
        if (turn.turnId != null) {
            observeRemoteCancel(turn, http.cancel(turn.turnId));
        } else {
            observeRemoteCancel(turn, http.cancelRequest(turn.requestId));
        }
    }

    private static void observeRemoteCancel(
            ActiveTurn turn, CompletableFuture<Void> cancellation) {
        cancellation.exceptionally(error -> {
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            ZapeGCitizens.LOGGER.warn(
                    "[citizen-audit] remote cancel failed request={} citizen={} error_type={}",
                    turn.requestId, turn.citizenId, cause.getClass().getSimpleName());
            return null;
        });
    }

    private static String friendlyError(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return "the shared brain is unavailable";
        }
        message = message.replace('\n', ' ').replace('\r', ' ').strip();
        return message.substring(0, Math.min(message.length(), 240));
    }

    private static void tellActor(ActiveTurn turn, String text) {
        ServerPlayer actor = turn.server.getPlayerList().getPlayer(turn.actorId);
        if (actor != null) {
            tell(actor, text);
        }
    }

    private static void tell(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }

    private static final class ActiveTurn {
        private final UUID requestId;
        private final MinecraftServer server;
        private final UUID actorId;
        private final String actorName;
        private final CitizenRegistryData.CitizenRecord record;
        private final UUID citizenId;
        private final InteractionMode mode;
        private final long startedNanos;
        private String turnId;
        private String executionId;
        private String providerToolCallId;
        private int toolSteps;
        private boolean cancelled;

        private ActiveTurn(
                UUID requestId,
                MinecraftServer server,
                UUID actorId,
                String actorName,
                CitizenRegistryData.CitizenRecord record,
                UUID citizenId,
                InteractionMode mode,
                long startedNanos) {
            this.requestId = requestId;
            this.server = server;
            this.actorId = actorId;
            this.actorName = actorName;
            this.record = record;
            this.citizenId = citizenId;
            this.mode = mode;
            this.startedNanos = startedNanos;
        }
    }

    public enum InteractionMode {
        TASK,
        DIALOGUE
    }
}
