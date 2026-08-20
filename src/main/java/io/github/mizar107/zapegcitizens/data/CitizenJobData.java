package io.github.mizar107.zapegcitizens.data;

import io.github.mizar107.zapegcitizens.ZapeGCitizens;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * World-authoritative ledger for long-running citizen jobs.
 *
 * <p>The Python brain retains the detailed plan and event history. This ledger deliberately keeps
 * the smaller recovery-critical subset next to the world: authority, original goal and anchor,
 * budgets, progress, and the exact action/result crossing the Minecraft/brain boundary.
 */
public final class CitizenJobData extends SavedData {

    public static final int DATA_VERSION = 2;
    /**
     * Progress phase marking a job that waits in line behind another job of the
     * same citizen. Waiting rows are the only allowed extra nonterminal rows per
     * citizen; the manager promotes the oldest one when the driving job ends.
     */
    public static final String WAITING_PHASE = "queued-waiting";
    public static final int MAX_GOAL_LENGTH = 8_000;
    public static final int MAX_PHASE_LENGTH = 128;
    public static final int MAX_SUMMARY_LENGTH = 2_048;
    public static final int MAX_MESSAGE_LENGTH = 2_048;
    public static final int MAX_ACTION_ARGUMENTS_LENGTH = 262_144;
    public static final int MAX_ACTION_RESULT_LENGTH = 16_000;
    public static final int MAX_TERMINAL_JOBS_PER_CITIZEN = 20;
    public static final int MAX_TOTAL_TERMINAL_JOBS = 1_000;

    private static final String DATA_NAME = ZapeGCitizens.MOD_ID + "_jobs";
    private static final String VERSION_TAG = "Version";
    private static final String JOBS_TAG = "Jobs";

    private final Map<UUID, JobRecord> jobs = new LinkedHashMap<>();

    public static CitizenJobData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(
                CitizenJobData::load, CitizenJobData::new, DATA_NAME);
    }

    public Optional<JobRecord> find(UUID jobId) {
        return Optional.ofNullable(jobs.get(Objects.requireNonNull(jobId, "jobId")));
    }

    public Optional<JobRecord> activeForCitizen(UUID citizenId) {
        Objects.requireNonNull(citizenId, "citizenId");
        return jobs.values().stream()
                .filter(job -> job.citizenId().equals(citizenId) && !job.state().terminal())
                .findFirst();
    }

    /** The one nonterminal job actually driving the citizen, ignoring waiting rows. */
    public Optional<JobRecord> activeDrivingForCitizen(UUID citizenId) {
        Objects.requireNonNull(citizenId, "citizenId");
        return jobs.values().stream()
                .filter(job -> job.citizenId().equals(citizenId)
                        && !job.state().terminal()
                        && !isWaitingInLine(job))
                .findFirst();
    }

    /** Oldest-first waiting jobs queued behind the citizen's driving job. */
    public List<JobRecord> waitingForCitizen(UUID citizenId) {
        Objects.requireNonNull(citizenId, "citizenId");
        return jobs.values().stream()
                .filter(job -> job.citizenId().equals(citizenId) && isWaitingInLine(job))
                .toList();
    }

    /** True for a nonterminal job parked in line behind another job. */
    public static boolean isWaitingInLine(JobRecord job) {
        return job != null
                && job.state() == JobState.QUEUED
                && WAITING_PHASE.equals(job.progress().phase());
    }

    public List<JobRecord> all() {
        return List.copyOf(jobs.values());
    }

    public List<JobRecord> forCitizen(UUID citizenId) {
        Objects.requireNonNull(citizenId, "citizenId");
        return jobs.values().stream()
                .filter(job -> job.citizenId().equals(citizenId))
                .toList();
    }

    /**
     * Creates a job while enforcing the one-driving-job-per-body invariant.
     * Waiting-in-line rows ({@link #WAITING_PHASE}) may coexist with the
     * driving job; every other nonterminal creation stays exclusive.
     */
    public void create(JobRecord job) {
        Objects.requireNonNull(job, "job");
        if (jobs.containsKey(job.jobId())) {
            throw new IllegalStateException("Job identity is already reserved: " + job.jobId());
        }
        if (!job.state().terminal() && !isWaitingInLine(job)
                && activeDrivingForCitizen(job.citizenId()).isPresent()) {
            throw new IllegalStateException(
                    "Citizen already has an active job: " + job.citizenId());
        }
        jobs.put(job.jobId(), job);
        pruneTerminalHistory();
        setDirty();
    }

    /** Applies one exact immutable transition and returns the resulting row. */
    public Optional<JobRecord> update(UUID jobId, UnaryOperator<JobRecord> transition) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(transition, "transition");
        JobRecord current = jobs.get(jobId);
        if (current == null) {
            return Optional.empty();
        }
        JobRecord updated = Objects.requireNonNull(transition.apply(current), "updated job");
        if (!updated.jobId().equals(current.jobId())
                || !updated.citizenId().equals(current.citizenId())) {
            throw new IllegalArgumentException("A job transition cannot change its identity");
        }
        if (!updated.equals(current)) {
            jobs.put(jobId, updated);
            pruneTerminalHistory();
            setDirty();
        }
        return Optional.of(updated);
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt(VERSION_TAG, DATA_VERSION);
        ListTag rows = new ListTag();
        for (JobRecord job : jobs.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("JobId", job.jobId());
            row.putUUID("StartRequestId", job.startRequestId());
            row.putUUID("CitizenId", job.citizenId());
            row.putUUID("ActorId", job.actorId());
            row.putString("ActorName", job.actorName());
            row.putString("Goal", job.goal());
            row.putString("State", job.state().name());
            row.put("ActorContext", saveActorContext(job.actorContext()));
            row.put("Budget", saveBudget(job.budget()));
            row.put("Progress", saveProgress(job.progress()));
            row.putInt("ActionsCompleted", job.actionsCompleted());
            row.putLong("ActiveTicks", job.activeTicks());
            row.putLong("CreatedGameTime", job.createdGameTime());
            row.putLong("UpdatedGameTime", job.updatedGameTime());
            job.pendingAction().ifPresent(action -> row.put("PendingAction", saveAction(action)));
            job.lastConfirmedActionId().ifPresent(
                    value -> row.putString("LastConfirmedActionId", value));
            job.message().ifPresent(value -> row.putString("Message", value));
            rows.add(row);
        }
        root.put(JOBS_TAG, rows);
        return root;
    }

    static CitizenJobData load(CompoundTag root) {
        CitizenJobData data = new CitizenJobData();
        ListTag rows = root.getList(JOBS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < rows.size(); index++) {
            JobRecord job = loadRow(rows.getCompound(index));
            if (job == null || data.jobs.containsKey(job.jobId())) {
                continue;
            }
            // Waiting-in-line rows legitimately coexist with the driving job;
            // any other duplicate nonterminal row for the citizen is dropped.
            if (!job.state().terminal() && !isWaitingInLine(job)
                    && data.activeDrivingForCitizen(job.citizenId()).isPresent()) {
                continue;
            }
            data.jobs.put(job.jobId(), job);
        }
        int version = root.contains(VERSION_TAG, Tag.TAG_INT) ? root.getInt(VERSION_TAG) : 0;
        if (version < DATA_VERSION && !data.jobs.isEmpty()) {
            data.setDirty();
        }
        int beforePrune = data.jobs.size();
        data.pruneTerminalHistory();
        if (data.jobs.size() != beforePrune) {
            data.setDirty();
        }
        return data;
    }

    private void pruneTerminalHistory() {
        List<UUID> citizens = jobs.values().stream()
                .map(JobRecord::citizenId)
                .distinct()
                .toList();
        for (UUID citizenId : citizens) {
            removeTerminalBeyond(
                    jobs.values().stream()
                            .filter(job -> job.citizenId().equals(citizenId)
                                    && job.state().terminal())
                            .sorted(Comparator.comparingLong(JobRecord::updatedGameTime).reversed())
                            .toList(),
                    MAX_TERMINAL_JOBS_PER_CITIZEN);
        }
        removeTerminalBeyond(
                jobs.values().stream()
                        .filter(job -> job.state().terminal())
                        .sorted(Comparator.comparingLong(JobRecord::updatedGameTime).reversed())
                        .toList(),
                MAX_TOTAL_TERMINAL_JOBS);
    }

    private void removeTerminalBeyond(List<JobRecord> terminal, int keep) {
        for (int index = keep; index < terminal.size(); index++) {
            jobs.remove(terminal.get(index).jobId());
        }
    }

    private static JobRecord loadRow(CompoundTag row) {
        try {
            if (!row.hasUUID("JobId")
                    || !row.hasUUID("StartRequestId")
                    || !row.hasUUID("CitizenId")
                    || !row.hasUUID("ActorId")
                    || !row.contains("ActorName", Tag.TAG_STRING)
                    || !row.contains("Goal", Tag.TAG_STRING)
                    || !row.contains("State", Tag.TAG_STRING)
                    || !row.contains("ActorContext", Tag.TAG_COMPOUND)
                    || !row.contains("Budget", Tag.TAG_COMPOUND)
                    || !row.contains("Progress", Tag.TAG_COMPOUND)) {
                return null;
            }
            ActorContext actorContext = loadActorContext(row.getCompound("ActorContext"));
            JobBudget budget = loadBudget(row.getCompound("Budget"));
            JobProgress progress = loadProgress(row.getCompound("Progress"));
            if (actorContext == null || budget == null || progress == null) {
                return null;
            }
            Optional<PendingAction> pending = row.contains("PendingAction", Tag.TAG_COMPOUND)
                    ? Optional.ofNullable(loadAction(row.getCompound("PendingAction")))
                    : Optional.empty();
            if (row.contains("PendingAction", Tag.TAG_COMPOUND) && pending.isEmpty()) {
                return null;
            }
            return new JobRecord(
                    row.getUUID("JobId"),
                    row.getUUID("StartRequestId"),
                    row.getUUID("CitizenId"),
                    row.getUUID("ActorId"),
                    row.getString("ActorName"),
                    row.getString("Goal"),
                    actorContext,
                    budget,
                    JobState.valueOf(row.getString("State")),
                    progress,
                    Math.max(0, row.getInt("ActionsCompleted")),
                    Math.max(0L, row.getLong("ActiveTicks")),
                    pending,
                    optionalString(row, "LastConfirmedActionId"),
                    optionalString(row, "Message"),
                    row.getLong("CreatedGameTime"),
                    row.getLong("UpdatedGameTime"));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }

    private static CompoundTag saveActorContext(ActorContext context) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", context.dimension());
        tag.putDouble("X", context.x());
        tag.putDouble("Y", context.y());
        tag.putDouble("Z", context.z());
        tag.putFloat("Yaw", context.yaw());
        tag.putFloat("Pitch", context.pitch());
        context.lookTarget().ifPresent(target -> {
            CompoundTag targetTag = new CompoundTag();
            targetTag.putString("Kind", target.kind());
            targetTag.putString("Dimension", target.dimension());
            targetTag.putDouble("X", target.x());
            targetTag.putDouble("Y", target.y());
            targetTag.putDouble("Z", target.z());
            target.id().ifPresent(value -> targetTag.putString("Id", value));
            tag.put("LookTarget", targetTag);
        });
        return tag;
    }

    private static ActorContext loadActorContext(CompoundTag tag) {
        try {
            Optional<LookTarget> target = Optional.empty();
            if (tag.contains("LookTarget", Tag.TAG_COMPOUND)) {
                CompoundTag targetTag = tag.getCompound("LookTarget");
                target = Optional.of(new LookTarget(
                        targetTag.getString("Kind"),
                        targetTag.getString("Dimension"),
                        targetTag.getDouble("X"),
                        targetTag.getDouble("Y"),
                        targetTag.getDouble("Z"),
                        optionalString(targetTag, "Id")));
            }
            return new ActorContext(
                    tag.getString("Dimension"),
                    tag.getDouble("X"),
                    tag.getDouble("Y"),
                    tag.getDouble("Z"),
                    tag.getFloat("Yaw"),
                    tag.getFloat("Pitch"),
                    target);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }

    private static CompoundTag saveBudget(JobBudget budget) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("MaxActions", budget.maxActions());
        tag.putInt("MaxModelCalls", budget.maxModelCalls());
        tag.putInt("MaxActiveSeconds", budget.maxActiveSeconds());
        return tag;
    }

    private static JobBudget loadBudget(CompoundTag tag) {
        try {
            return new JobBudget(
                    tag.getInt("MaxActions"),
                    tag.getInt("MaxModelCalls"),
                    tag.getInt("MaxActiveSeconds"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static CompoundTag saveProgress(JobProgress progress) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Phase", progress.phase());
        tag.putString("Summary", progress.summary());
        return tag;
    }

    private static JobProgress loadProgress(CompoundTag tag) {
        try {
            return new JobProgress(tag.getString("Phase"), tag.getString("Summary"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static CompoundTag saveAction(PendingAction action) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ActionId", action.actionId());
        tag.putString("ExecutionId", action.executionId());
        tag.putString("ToolName", action.toolName());
        tag.putByteArray(
                "ArgumentsJsonUtf8", action.argumentsJson().getBytes(StandardCharsets.UTF_8));
        tag.putBoolean("ReadOnly", action.readOnly());
        tag.putBoolean("Uncertain", action.uncertain());
        action.resultJson().ifPresent(value -> tag.putByteArray(
                "ResultJsonUtf8", value.getBytes(StandardCharsets.UTF_8)));
        return tag;
    }

    private static PendingAction loadAction(CompoundTag tag) {
        try {
            return new PendingAction(
                    tag.getString("ActionId"),
                    tag.getString("ExecutionId"),
                    tag.getString("ToolName"),
                    requiredUtf8(tag, "ArgumentsJsonUtf8", "ArgumentsJson"),
                    tag.getBoolean("ReadOnly"),
                    tag.getBoolean("Uncertain"),
                    optionalUtf8(tag, "ResultJsonUtf8", "ResultJson"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String requiredUtf8(
            CompoundTag tag, String byteArrayName, String legacyStringName) {
        if (tag.contains(byteArrayName, Tag.TAG_BYTE_ARRAY)) {
            return new String(tag.getByteArray(byteArrayName), StandardCharsets.UTF_8);
        }
        return tag.getString(legacyStringName);
    }

    private static Optional<String> optionalUtf8(
            CompoundTag tag, String byteArrayName, String legacyStringName) {
        if (tag.contains(byteArrayName, Tag.TAG_BYTE_ARRAY)) {
            return Optional.of(
                    new String(tag.getByteArray(byteArrayName), StandardCharsets.UTF_8));
        }
        return optionalString(tag, legacyStringName);
    }

    private static Optional<String> optionalString(CompoundTag tag, String name) {
        return tag.contains(name, Tag.TAG_STRING)
                ? Optional.of(tag.getString(name))
                : Optional.empty();
    }

    public enum JobState {
        QUEUED,
        RUNNING,
        WAITING_ACTION,
        REPORTING_RESULT,
        NEEDS_INPUT,
        PAUSED,
        PAUSED_BODY,
        PAUSED_OWNER,
        PAUSED_BRAIN,
        PAUSED_BUDGET,
        PAUSED_SHUTDOWN,
        CANCELING,
        COMPLETED,
        CANCELED,
        FAILED;

        public boolean terminal() {
            return this == COMPLETED || this == CANCELED || this == FAILED;
        }

        public boolean consumesActiveTime() {
            return this == RUNNING || this == WAITING_ACTION || this == REPORTING_RESULT;
        }
    }

    public record JobBudget(int maxActions, int maxModelCalls, int maxActiveSeconds) {
        public JobBudget {
            if (maxActions < 1 || maxActions > 4_096) {
                throw new IllegalArgumentException("maxActions must be between 1 and 4096");
            }
            if (maxModelCalls < 1 || maxModelCalls > 8_192) {
                throw new IllegalArgumentException("maxModelCalls must be between 1 and 8192");
            }
            if (maxActiveSeconds < 30 || maxActiveSeconds > 2_592_000) {
                throw new IllegalArgumentException(
                        "maxActiveSeconds must be between 30 and 2592000");
            }
        }
    }

    public record JobProgress(String phase, String summary) {
        public JobProgress {
            phase = boundedText(phase, "phase", MAX_PHASE_LENGTH, true);
            summary = boundedText(summary, "summary", MAX_SUMMARY_LENGTH, true);
        }

        public static JobProgress queued() {
            return new JobProgress("queued", "Waiting for the shared brain.");
        }
    }

    /** Snapshot of the submitting player's spatial context. */
    public record ActorContext(
            String dimension,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            Optional<LookTarget> lookTarget) {

        public ActorContext {
            dimension = boundedText(dimension, "dimension", 128, false);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("actor coordinates and rotation must be finite");
            }
            lookTarget = Objects.requireNonNull(lookTarget, "lookTarget");
            lookTarget.ifPresent(value -> Objects.requireNonNull(value, "look target value"));
        }

        public static ActorContext capture(ServerPlayer actor) {
            Objects.requireNonNull(actor, "actor");
            Optional<LookTarget> target = Optional.empty();
            HitResult hit = actor.pick(20.0D, 1.0F, false);
            if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
                target = Optional.of(new LookTarget(
                        "BLOCK",
                        actor.serverLevel().dimension().location().toString(),
                        block.getBlockPos().getX(),
                        block.getBlockPos().getY(),
                        block.getBlockPos().getZ(),
                        Optional.of(block.getDirection().getName())));
            }
            return new ActorContext(
                    actor.serverLevel().dimension().location().toString(),
                    actor.getX(),
                    actor.getY(),
                    actor.getZ(),
                    actor.getYRot(),
                    actor.getXRot(),
                    target);
        }
    }

    public record LookTarget(
            String kind,
            String dimension,
            double x,
            double y,
            double z,
            Optional<String> id) {

        public LookTarget {
            kind = boundedText(kind, "look target kind", 16, false).toUpperCase();
            if (!kind.equals("BLOCK") && !kind.equals("ENTITY")) {
                throw new IllegalArgumentException("look target kind must be BLOCK or ENTITY");
            }
            dimension = boundedText(dimension, "look target dimension", 128, false);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("look target coordinates must be finite");
            }
            id = Objects.requireNonNull(id, "id")
                    .map(value -> boundedText(value, "look target id", 128, false));
        }
    }

    /** One issued action, retained until the brain acknowledges its result. */
    public record PendingAction(
            String actionId,
            String executionId,
            String toolName,
            String argumentsJson,
            boolean readOnly,
            boolean uncertain,
            Optional<String> resultJson) {

        public PendingAction {
            actionId = boundedText(actionId, "action id", 128, false);
            executionId = boundedText(executionId, "execution id", 128, false);
            toolName = boundedText(toolName, "tool name", 64, false);
            argumentsJson = boundedText(
                    argumentsJson,
                    "action arguments",
                    MAX_ACTION_ARGUMENTS_LENGTH,
                    false);
            resultJson = Objects.requireNonNull(resultJson, "resultJson")
                    .map(value -> boundedText(
                            value,
                            "action result",
                            MAX_ACTION_RESULT_LENGTH,
                            true));
        }

        public PendingAction markUncertain() {
            return new PendingAction(
                    actionId, executionId, toolName, argumentsJson, readOnly, true, resultJson);
        }

        public PendingAction withResult(String result) {
            return new PendingAction(
                    actionId,
                    executionId,
                    toolName,
                    argumentsJson,
                    readOnly,
                    false,
                    Optional.of(Objects.requireNonNull(result, "result")));
        }
    }

    public record JobRecord(
            UUID jobId,
            UUID startRequestId,
            UUID citizenId,
            UUID actorId,
            String actorName,
            String goal,
            ActorContext actorContext,
            JobBudget budget,
            JobState state,
            JobProgress progress,
            int actionsCompleted,
            long activeTicks,
            Optional<PendingAction> pendingAction,
            Optional<String> lastConfirmedActionId,
            Optional<String> message,
            long createdGameTime,
            long updatedGameTime) {

        public JobRecord {
            jobId = Objects.requireNonNull(jobId, "jobId");
            startRequestId = Objects.requireNonNull(startRequestId, "startRequestId");
            citizenId = Objects.requireNonNull(citizenId, "citizenId");
            actorId = Objects.requireNonNull(actorId, "actorId");
            actorName = boundedText(actorName, "actor name", 64, false);
            goal = boundedText(goal, "goal", MAX_GOAL_LENGTH, false);
            actorContext = Objects.requireNonNull(actorContext, "actorContext");
            budget = Objects.requireNonNull(budget, "budget");
            state = Objects.requireNonNull(state, "state");
            progress = Objects.requireNonNull(progress, "progress");
            if (actionsCompleted < 0 || actionsCompleted > budget.maxActions()) {
                throw new IllegalArgumentException("actionsCompleted is outside the job budget");
            }
            if (activeTicks < 0L) {
                throw new IllegalArgumentException("activeTicks must not be negative");
            }
            pendingAction = Objects.requireNonNull(pendingAction, "pendingAction");
            lastConfirmedActionId = Objects.requireNonNull(
                    lastConfirmedActionId, "lastConfirmedActionId")
                    .map(value -> boundedText(value, "last confirmed action id", 128, false));
            message = Objects.requireNonNull(message, "message")
                    .map(value -> boundedText(value, "message", MAX_MESSAGE_LENGTH, true));
        }

        public JobRecord transition(
                JobState nextState,
                JobProgress nextProgress,
                int nextActionsCompleted,
                long nextActiveTicks,
                Optional<PendingAction> nextPendingAction,
                Optional<String> nextLastConfirmedActionId,
                Optional<String> nextMessage,
                long now) {
            return new JobRecord(
                    jobId,
                    startRequestId,
                    citizenId,
                    actorId,
                    actorName,
                    goal,
                    actorContext,
                    budget,
                    nextState,
                    nextProgress,
                    nextActionsCompleted,
                    nextActiveTicks,
                    nextPendingAction,
                    nextLastConfirmedActionId,
                    nextMessage,
                    createdGameTime,
                    now);
        }
    }

    private static String boundedText(
            String value, String label, int maximum, boolean allowEmpty) {
        String normalized = Objects.requireNonNull(value, label).strip();
        if ((!allowEmpty && normalized.isEmpty()) || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    label + " must contain " + (allowEmpty ? "0" : "1") + "-" + maximum
                            + " characters");
        }
        return normalized;
    }
}
