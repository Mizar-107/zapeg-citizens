package io.github.mizar107.zapegcitizens.brain;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mizar107.zapegcitizens.ZapeGCitizens;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.CitizenIdentity;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.JobAction;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.JobReply;
import io.github.mizar107.zapegcitizens.brain.BrainProtocol.JobReplyKind;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat;
import io.github.mizar107.zapegcitizens.compat.brain.NumenToolGateway;
import io.github.mizar107.zapegcitizens.data.CitizenJobData;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.ActorContext;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobBudget;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobProgress;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobRecord;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobState;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.PendingAction;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.CitizenRecord;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.OwnerKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Durable, one-action-at-a-time coordinator for long-running physical jobs.
 *
 * <p>Unlike dialogue turns, jobs are reconstructed from world SavedData after restart. The exact
 * pending action remains persisted until its result has been accepted by the brain. A physical
 * action whose callback was lost is marked uncertain and is never blindly replayed.
 */
public final class CitizenJobManager {

    private static final CitizenJobManager INSTANCE = new CitizenJobManager();
    private static final int BUDGET_ACCOUNT_INTERVAL_TICKS = 20;
    private static final long BRAIN_RETRY_BASE_TICKS = 100L;
    private static final long BRAIN_RETRY_MAX_TICKS = 1_200L;
    private static final int BRAIN_RETRY_MAX_ATTEMPTS = 6;
    /** After the exponential backoff is exhausted, cancel retries drop to five minutes. */
    private static final long CANCEL_RETRY_SLOW_TICKS = 6_000L;
    private static final long AUTO_RESUME_DEBOUNCE_TICKS = 200L;
    private static final int AUTO_RESUME_MAX_ATTEMPTS = 4;
    private static final int MAX_INVENTORY_DIFF_LENGTH = 240;
    /**
     * Per-action physical watchdog. A Numen task whose completion callback never
     * arrives (unreachable goto target, exhausted mine search, dropped task) must
     * not wedge the job in WAITING_ACTION until the multi-hour active-time budget
     * kills it: the pending action is canceled and a machine-readable timeout
     * failure is reported so the planner re-plans and the actor hears one line.
     */
    private static final long ACTION_TIMEOUT_TICKS = 20L * 240L;
    private static final long LONG_ACTION_TIMEOUT_TICKS = 20L * 720L;
    private static final Set<String> LONG_RUNNING_TOOLS = Set.of("mine", "build", "fish");
    private static final long FAILURE_NOTICE_MIN_INTERVAL_TICKS = 100L;
    private static final int MAX_WAITING_JOBS_PER_CITIZEN = 2;
    private static final int GOAL_SNIPPET_LENGTH = 60;
    static final String PROVIDER_UNAVAILABLE_PREFIX = "provider_unavailable";
    static final String PLANNING_IN_PROGRESS_PREFIX = "planning_in_progress";
    static final String STAGE_BUDGET_PREFIX = "stage_budget_exhausted";
    /** Brain 409 code meaning "healthy, still planning" — never an outage. */
    static final String JOB_IN_PROGRESS_CODE = "job_in_progress";
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "get_self_status",
            "get_owner_status",
            "get_world_info",
            "look_around",
            "scan_nearby_entities",
            "scan_blocks",
            "inspect_block",
            "locate_structure",
            "locate_biome",
            "lookup_recipe",
            "load_skill",
            "blueprint_read",
            "task_status",
            "inspect_gui",
            "inspect_block_storage");

    private final Optional<BrainConfig> config;
    private final BrainHttpClient http;
    /**
     * Per-job generation token for the one HTTP operation whose reply may still mutate local
     * state. Removing or replacing the token invalidates every late completion from an older
     * request without relying on best-effort HTTP cancellation.
     */
    private final Map<UUID, UUID> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> cancelInFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextCancelRetryAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextBrainRetryAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> brainRetryAttempts = new ConcurrentHashMap<>();
    private final Set<UUID> brainPauseNotified = ConcurrentHashMap.newKeySet();
    /**
     * Runtime-only auto-resume bookkeeping for NEEDS_INPUT jobs: the inventory snapshot taken
     * when the requirement was declared, plus debounce state and the per-requirement bounded
     * attempt budget. None of this is persisted; after a restart the first tick simply
     * re-baselines the snapshot.
     */
    private final Map<UUID, Map<String, Integer>> needsInputInventoryBaseline =
            new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextAutoResumeAt = new ConcurrentHashMap<>();
    private final AutoResumeBudget autoResumeBudget =
            new AutoResumeBudget(AUTO_RESUME_MAX_ATTEMPTS);
    private final Set<UUID> autoResumeExhaustedNotified = ConcurrentHashMap.newKeySet();
    /** Runtime-only action-watchdog, cancel-retry, and failure-notice bookkeeping. */
    private final Map<UUID, Long> actionDispatchedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> cancelRetryAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFailureNoticeAt = new ConcurrentHashMap<>();

    private MinecraftServer attachedServer;
    private long lastBudgetAccountAt;

    private CitizenJobManager() {
        this.config = BrainConfig.fromEnvironment();
        this.http = config.map(BrainHttpClient::new).orElse(null);
    }

    public static CitizenJobManager instance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return http != null;
    }

    public int inFlightCount() {
        return inFlight.size() + cancelInFlight.size();
    }

    /** Creates and persists a job before its first HTTP request. */
    public JobOperation submit(
            ServerPlayer actor,
            CitizenRecord citizenRecord,
            NumenPlayer citizen,
            String requestedGoal) {
        if (http == null) {
            return JobOperation.failure(
                    "The shared brain is not configured. Ask the host to start it.");
        }
        if (actor == null || citizenRecord == null || citizen == null) {
            return JobOperation.failure("The actor, citizen record, and live body are required.");
        }
        String goal = requestedGoal == null ? "" : requestedGoal.strip();
        if (goal.isEmpty() || goal.length() > CitizenJobData.MAX_GOAL_LENGTH) {
            return JobOperation.failure(
                    "Jobs must contain 1-" + CitizenJobData.MAX_GOAL_LENGTH + " characters.");
        }
        if (!citizen.getUUID().equals(citizenRecord.citizenId())) {
            return JobOperation.failure("The live body does not match the managed citizen record.");
        }

        MinecraftServer server = actor.server;
        CitizenJobData data = CitizenJobData.get(server);
        boolean waitInLine = data.activeForCitizen(citizenRecord.citizenId()).isPresent();
        if (waitInLine) {
            int waiting = data.waitingForCitizen(citizenRecord.citizenId()).size();
            if (waiting >= MAX_WAITING_JOBS_PER_CITIZEN) {
                return JobOperation.failure(
                        citizenRecord.name() + " meşgul ve görev sırası dolu ("
                                + MAX_WAITING_JOBS_PER_CITIZEN
                                + " bekleyen iş). Önce birini bitirmesini bekle veya iptal et.");
            }
        }

        BrainConfig settings = config.orElseThrow();
        long now = server.overworld().getGameTime();
        JobRecord job = new JobRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                citizenRecord.citizenId(),
                actor.getUUID(),
                actor.getGameProfile().getName(),
                goal,
                ActorContext.capture(actor),
                new JobBudget(
                        settings.maxJobActions(),
                        settings.maxJobModelCalls(),
                        Math.toIntExact(settings.maxJobActiveTime().toSeconds())),
                JobState.QUEUED,
                waitInLine
                        ? new JobProgress(
                                CitizenJobData.WAITING_PHASE,
                                "Waiting for the citizen's current job to finish.")
                        : JobProgress.queued(),
                0,
                0L,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now,
                now);
        try {
            data.create(job);
        } catch (IllegalStateException exception) {
            return JobOperation.failure(cleanMessage(exception));
        }
        if (!flushLedger(server, job, waitInLine ? "queued job" : "job acceptance")) {
            failLocal(server, job, "the world job ledger could not be saved");
            return JobOperation.failure(
                    "The job ledger could not be saved; no work was started.");
        }

        if (waitInLine) {
            int position = data.waitingForCitizen(citizenRecord.citizenId()).size();
            ZapeGCitizens.LOGGER.info(
                    "[citizen-job] queued job={} actor={} citizen={} position={}",
                    job.jobId(), job.actorId(), job.citizenId(), position);
            JobOperation queued = JobOperation.success(
                    job.jobId(),
                    citizenRecord.name() + " şu an meşgul; yeni görev sıraya alındı (#"
                            + position + "): " + goalSnippet(goal));
            actor.sendSystemMessage(Component.literal("[Citizens] " + queued.message()));
            return queued;
        }

        ZapeGCitizens.LOGGER.info(
                "[citizen-job] accepted job={} actor={} citizen={} goal_chars={}",
                job.jobId(), job.actorId(), job.citizenId(), goal.length());
        startRemote(server, job, citizenRecord, false);
        JobOperation accepted = JobOperation.success(
                job.jobId(),
                citizenRecord.name() + " görevi kabul etti (" + shortId(job.jobId()) + ").");
        actor.sendSystemMessage(Component.literal("[Citizens] " + accepted.message()));
        return accepted;
    }

    /** Returns the active job for a citizen without contacting the model. */
    public Optional<JobRecord> status(MinecraftServer server, UUID citizenId) {
        return controllable(server, citizenId);
    }

    /**
     * The job a player command should address: the driving job when one exists,
     * otherwise the oldest other nonterminal row (a budget-parked or waiting
     * one), so parked jobs remain reachable for status and cancellation without
     * shadowing live work.
     */
    private static Optional<JobRecord> controllable(MinecraftServer server, UUID citizenId) {
        CitizenJobData data = CitizenJobData.get(server);
        Optional<JobRecord> driving = data.activeDrivingForCitizen(citizenId);
        return driving.isPresent() ? driving : data.activeForCitizen(citizenId);
    }

    public Optional<JobRecord> find(MinecraftServer server, UUID jobId) {
        return CitizenJobData.get(server).find(jobId);
    }

    public List<JobRecord> history(MinecraftServer server, UUID citizenId) {
        return CitizenJobData.get(server).forCitizen(citizenId);
    }

    /**
     * Immediately revokes local/physical authority, then durably blocks replacement work until
     * the sidecar has acknowledged cancellation (or confirmed that the job was already absent).
     */
    public JobOperation cancel(
            MinecraftServer server, CitizenRecord citizenRecord, String requestedReason) {
        JobRecord job = controllable(server, citizenRecord.citizenId()).orElse(null);
        if (job == null) {
            return JobOperation.failure(citizenRecord.name() + " has no active job.");
        }
        if (job.state() == JobState.CANCELING) {
            if (!continueCancellation(server, job, citizenRecord, "cancellation retry")) {
                return JobOperation.failure(
                        "Cancellation is pending but its ledger record could not be saved.");
            }
            return JobOperation.success(
                    job.jobId(), "Cancellation is still pending for job "
                            + shortId(job.jobId()) + ".");
        }
        String reason = boundedReason(requestedReason, "canceled by an operator");
        long now = server.overworld().getGameTime();
        JobRecord canceling = CitizenJobData.get(server).update(job.jobId(), current ->
                current.transition(
                        JobState.CANCELING,
                        current.progress(),
                        current.actionsCompleted(),
                        current.activeTicks(),
                        current.pendingAction(),
                        current.lastConfirmedActionId(),
                        Optional.of(reason),
                        now)).orElseThrow();
        inFlight.remove(job.jobId());
        if (!continueCancellation(server, canceling, citizenRecord, "cancellation intent")) {
            return JobOperation.failure(
                    "Cancellation could not be durably recorded; physical work was not canceled.");
        }
        return JobOperation.success(
                job.jobId(), "Cancellation requested for job " + shortId(job.jobId()) + ".");
    }

    /**
     * "Stop" means stand down completely: cancels the job the citizen is
     * working on AND terminally cancels every waiting-in-line row, so the queue
     * cannot promote a replacement seconds after the player said stop. Waiting
     * rows are brain-unknown (their remote start happens only at promotion), so
     * they are canceled locally without a remote handshake; only the
     * driving/parked job goes through the durable CANCELING acknowledgement.
     * The player hears one Turkish line; "cancel"/"iptal" keeps the
     * one-job-at-a-time semantics through {@link #cancel} instead.
     */
    public JobOperation stopAll(
            MinecraftServer server, CitizenRecord citizenRecord, String requestedReason) {
        CitizenJobData data = CitizenJobData.get(server);
        String reason = boundedReason(requestedReason, "stopped by its owner");
        long now = server.overworld().getGameTime();
        List<JobRecord> waiting = data.waitingForCitizen(citizenRecord.citizenId());
        for (JobRecord waitingJob : waiting) {
            data.update(waitingJob.jobId(), current -> current.transition(
                    JobState.CANCELED,
                    current.progress(),
                    current.actionsCompleted(),
                    current.activeTicks(),
                    current.pendingAction(),
                    current.lastConfirmedActionId(),
                    Optional.of(reason),
                    now));
            ZapeGCitizens.LOGGER.info(
                    "[citizen-job] stop cleared waiting job={} citizen={}",
                    waitingJob.jobId(), citizenRecord.citizenId());
        }
        if (!waiting.isEmpty()) {
            flushLedger(server, waiting.get(0), "stop cleared the waiting queue");
        }
        JobRecord active = controllable(server, citizenRecord.citizenId()).orElse(null);
        if (active == null) {
            return waiting.isEmpty()
                    ? JobOperation.failure(citizenRecord.name() + " has no active job.")
                    : JobOperation.success(
                            waiting.get(0).jobId(), "Tamam, her şeyi bırakıyorum.");
        }
        JobOperation cancellation = cancel(server, citizenRecord, reason);
        if (!cancellation.successful()) {
            return cancellation;
        }
        return JobOperation.success(cancellation.jobId(), "Tamam, her şeyi bırakıyorum.");
    }

    /** Resume a paused/input-blocked job. A nonblank answer satisfies NEEDS_INPUT. */
    public JobOperation resume(
            MinecraftServer server,
            CitizenRecord citizenRecord,
            String answer) {
        JobRecord job = controllable(server, citizenRecord.citizenId()).orElse(null);
        if (job == null) {
            return JobOperation.failure(citizenRecord.name() + " has no resumable job.");
        }
        return resume(server, job.jobId(), answer);
    }

    public JobOperation resume(MinecraftServer server, UUID jobId, String requestedAnswer) {
        if (http == null) {
            return JobOperation.failure("The shared brain is not configured.");
        }
        JobRecord job = CitizenJobData.get(server).find(jobId).orElse(null);
        if (job == null) {
            return JobOperation.failure("No durable job named " + shortId(jobId) + ".");
        }
        if (job.state().terminal()) {
            return JobOperation.failure("Job " + shortId(jobId) + " is already terminal.");
        }
        if (job.state() == JobState.CANCELING) {
            continueCancellation(server, job, null, "resume encountered cancellation");
            return JobOperation.failure(
                    "Job " + shortId(jobId) + " is waiting for cancellation acknowledgement.");
        }
        if (job.state() == JobState.PAUSED_BUDGET) {
            return JobOperation.failure(
                    "Job " + shortId(jobId) + " exhausted its configured budget and cannot resume.");
        }
        String answer = requestedAnswer == null ? "" : requestedAnswer.strip();
        if (answer.length() > CitizenJobData.MAX_GOAL_LENGTH) {
            return JobOperation.failure(
                    "Job answers must contain at most "
                            + CitizenJobData.MAX_GOAL_LENGTH + " characters.");
        }
        if (job.state() == JobState.NEEDS_INPUT && answer.isEmpty()) {
            return JobOperation.failure("This job needs an answer before it can resume.");
        }
        if (inFlight.containsKey(jobId)) {
            return JobOperation.failure("The job already has a brain request in flight.");
        }
        // The first request can fail before Python sees it, or its reply can be lost after
        // Python commits it. Reissuing the original /start body with the original idempotency
        // key safely covers both windows; /resume cannot recover a job Python never created.
        if (shouldRetryInitialStart(job)) {
            CitizenRecord citizenRecord = CitizenRegistryData.get(server)
                    .findByCitizenId(job.citizenId()).orElse(null);
            if (citizenRecord == null || !canRun(server, citizenRecord)
                    || NumenServerCompat.findLiveManaged(
                            server, citizenRecord.citizenId(), citizenRecord.bodyOwnerId()) == null) {
                return JobOperation.failure(
                        "The citizen or its player owner is not currently available.");
            }
            startRemote(server, job, citizenRecord, true);
            return JobOperation.success(
                    jobId, "Retrying initial start for job " + shortId(jobId) + ".");
        }
        if (job.pendingAction().flatMap(PendingAction::resultJson).isPresent()) {
            reportStoredResult(server, job, true);
            return JobOperation.success(jobId, "Retrying the stored result for " + shortId(jobId) + ".");
        }
        if (!isResumableState(job.state())) {
            return JobOperation.failure(
                    "Job " + shortId(jobId) + " is already "
                            + job.state().name().toLowerCase(Locale.ROOT)
                            + "; wait for it or cancel it before resuming.");
        }
        CitizenRecord citizenRecord = CitizenRegistryData.get(server)
                .findByCitizenId(job.citizenId()).orElse(null);
        if (citizenRecord == null) {
            return JobOperation.failure("The job's managed citizen no longer exists.");
        }
        if (!canRun(server, citizenRecord)) {
            return JobOperation.failure("The citizen or its player owner is not currently available.");
        }
        NumenPlayer body = NumenServerCompat.findLiveManaged(
                server, citizenRecord.citizenId(), citizenRecord.bodyOwnerId());
        if (body == null) {
            return JobOperation.failure("The citizen body is dormant or recovering.");
        }

        long now = server.overworld().getGameTime();
        JobRecord resumable = CitizenJobData.get(server).update(jobId, current -> {
            Optional<PendingAction> pending = current.pendingAction()
                    .map(action -> action.resultJson().isPresent()
                            ? action
                            : action.markUncertain());
            return current.transition(
                    JobState.RUNNING,
                    current.progress(),
                    current.actionsCompleted(),
                    current.activeTicks(),
                    pending,
                    current.lastConfirmedActionId(),
                    Optional.empty(),
                    now);
        }).orElseThrow();
        UUID requestId = UUID.randomUUID();
        String interruptedActionId = resumable.pendingAction()
                .filter(action -> action.resultJson().isEmpty())
                .map(PendingAction::actionId)
                .orElse(null);
        beginRemote(
                server,
                jobId,
                () -> http.resumeJob(requestId, resumable, answer),
                null,
                interruptedActionId,
                false);
        return JobOperation.success(jobId, "Resuming job " + shortId(jobId) + ".");
    }

    /**
     * Targeted login/body-recovery hook. It never reconciles or disturbs a live WAITING_ACTION job.
     */
    public JobOperation resumeEligible(MinecraftServer server, UUID citizenId) {
        JobRecord job = CitizenJobData.get(server).activeDrivingForCitizen(citizenId).orElse(null);
        if (job == null) {
            return JobOperation.failure("The citizen has no active job.");
        }
        if (job.state() != JobState.PAUSED_BODY && job.state() != JobState.PAUSED_OWNER) {
            return JobOperation.failure("The active job is not waiting for a body or owner.");
        }
        if (inFlight.containsKey(job.jobId())) {
            return JobOperation.failure("The job already has a brain request in flight.");
        }
        CitizenRecord record = CitizenRegistryData.get(server)
                .findByCitizenId(citizenId).orElse(null);
        if (record == null || !canRun(server, record)
                || NumenServerCompat.findLiveManaged(
                        server, record.citizenId(), record.bodyOwnerId()) == null) {
            return JobOperation.failure("The citizen or its player owner is not ready yet.");
        }
        return resume(server, job.jobId(), "");
    }

    /** Rebuilds runtime routes after server start without replaying uncertain physical work. */
    public int reconcile(MinecraftServer server) {
        attach(server);
        int scheduled = 0;
        for (JobRecord job : CitizenJobData.get(server).all()) {
            if (job.state().terminal()
                    || job.state() == JobState.NEEDS_INPUT
                    || job.state() == JobState.PAUSED
                    || job.state() == JobState.PAUSED_BRAIN
                    || job.state() == JobState.PAUSED_BUDGET) {
                continue;
            }
            if (CitizenJobData.isWaitingInLine(job)) {
                // Waiting-in-line jobs have no remote state to recover; the
                // tick-time promotion starts them when their turn comes.
                continue;
            }
            if (job.state() == JobState.CANCELING) {
                continueCancellation(server, job, null, "startup cancellation recovery");
                scheduled++;
                continue;
            }
            if (job.state() == JobState.PAUSED_BODY || job.state() == JobState.PAUSED_OWNER) {
                continue;
            }
            CitizenRecord citizenRecord = CitizenRegistryData.get(server)
                    .findByCitizenId(job.citizenId()).orElse(null);
            if (citizenRecord == null) {
                failLocal(server, job, "the managed citizen no longer exists");
                continue;
            }
            if (!canRun(server, citizenRecord)) {
                pauseLocal(
                        server,
                        job,
                        "waiting for the citizen's player owner",
                        false,
                        JobState.PAUSED_OWNER);
                continue;
            }
            if (NumenServerCompat.findLiveManaged(
                    server, citizenRecord.citizenId(), citizenRecord.bodyOwnerId()) == null) {
                pauseLocal(
                        server, job, "waiting for the citizen body", false, JobState.PAUSED_BODY);
                continue;
            }
            if (job.pendingAction().flatMap(PendingAction::resultJson).isPresent()) {
                reportStoredResult(server, job, true);
                scheduled++;
                continue;
            }
            if (job.pendingAction().isPresent()) {
                long now = server.overworld().getGameTime();
                job = CitizenJobData.get(server).update(job.jobId(), current ->
                        current.transition(
                                JobState.PAUSED,
                                current.progress(),
                                current.actionsCompleted(),
                                current.activeTicks(),
                                current.pendingAction().map(PendingAction::markUncertain),
                                current.lastConfirmedActionId(),
                                Optional.of("recovering an interrupted action"),
                                now)).orElseThrow();
            }
            if (needsPlanningRecovery(job)) {
                // A mid-planning RUNNING job whose in-flight brain reply was lost to a restart
                // has no driver and no pending action, and resume() rejects RUNNING. Move it to
                // a resumable pause so the brain continues from its durable checkpoint.
                long now = server.overworld().getGameTime();
                job = CitizenJobData.get(server).update(job.jobId(), current ->
                        current.transition(
                                JobState.PAUSED,
                                current.progress(),
                                current.actionsCompleted(),
                                current.activeTicks(),
                                current.pendingAction(),
                                current.lastConfirmedActionId(),
                                Optional.of("recovering an interrupted planning step"),
                                now)).orElseThrow();
            }
            JobOperation operation = resume(server, job.jobId(), "");
            if (operation.successful()) {
                scheduled++;
            }
        }
        return scheduled;
    }

    /** Pause work when a body disappears; lifecycle recovery can later call reconcile/resume. */
    public void bodyUnavailable(NumenPlayer citizen, String reason) {
        if (citizen == null) {
            return;
        }
        MinecraftServer server = citizen.server;
        CitizenJobData.get(server).activeDrivingForCitizen(citizen.getUUID())
                .ifPresent(job -> pauseLocal(
                        server,
                        job,
                        boundedReason(reason, "body unavailable"),
                        true,
                        JobState.PAUSED_BODY));
    }

    /** Player-owned jobs pause on logout instead of losing their plan. */
    public void ownerUnavailable(MinecraftServer server, UUID ownerId) {
        for (CitizenRecord record : CitizenRegistryData.get(server).ownedBy(ownerId)) {
            CitizenJobData.get(server).activeDrivingForCitizen(record.citizenId())
                    .ifPresent(job -> pauseLocal(
                            server,
                            job,
                            "player owner logged out",
                            true,
                            JobState.PAUSED_OWNER));
        }
    }

    /**
     * Quarantines logical work without touching a live body whose technical ownership is corrupt.
     */
    public void ownershipMismatch(MinecraftServer server, UUID citizenId, String reason) {
        CitizenJobData.get(server).activeDrivingForCitizen(citizenId).ifPresent(job -> {
            if (job.state() == JobState.CANCELING) {
                continueCancellation(server, job, null, "ownership quarantine cancellation");
            } else {
                pauseLocal(
                        server,
                        job,
                        boundedReason(reason, "managed body ownership mismatch"),
                        false,
                        JobState.PAUSED);
            }
        });
    }

    /** Accounts only running time and pauses a job before it exceeds its active-time budget. */
    public void tick(MinecraftServer server) {
        attach(server);
        long now = server.overworld().getGameTime();
        long delta = now - lastBudgetAccountAt;
        if (delta < BUDGET_ACCOUNT_INTERVAL_TICKS) {
            return;
        }
        lastBudgetAccountAt = now;
        long boundedDelta = Math.min(delta, BUDGET_ACCOUNT_INTERVAL_TICKS * 5L);
        for (JobRecord job : CitizenJobData.get(server).all()) {
            if (job.state() == JobState.CANCELING) {
                long retryAt = nextCancelRetryAt.getOrDefault(job.jobId(), 0L);
                if (now >= retryAt) {
                    continueCancellation(server, job, null, "scheduled cancellation retry");
                }
                continue;
            }
            if (!job.state().consumesActiveTime()) {
                continue;
            }
            long nextTicks = job.activeTicks() + boundedDelta;
            if (nextTicks / 20L >= job.budget().maxActiveSeconds()) {
                pauseLocal(
                        server,
                        job,
                        "active-time budget exhausted",
                        true,
                        JobState.PAUSED_BUDGET);
                continue;
            }
            CitizenJobData.get(server).update(job.jobId(), current -> current.transition(
                    current.state(),
                    current.progress(),
                    current.actionsCompleted(),
                    nextTicks,
                    current.pendingAction(),
                    current.lastConfirmedActionId(),
                    current.message(),
                    now));
        }
        for (JobRecord job : CitizenJobData.get(server).all()) {
            if (job.state().terminal()) {
                nextBrainRetryAt.remove(job.jobId());
                brainRetryAttempts.remove(job.jobId());
                brainPauseNotified.remove(job.jobId());
                needsInputInventoryBaseline.remove(job.jobId());
                nextAutoResumeAt.remove(job.jobId());
                autoResumeBudget.clear(job.jobId());
                autoResumeExhaustedNotified.remove(job.jobId());
                actionDispatchedAt.remove(job.jobId());
                cancelRetryAttempts.remove(job.jobId());
                lastFailureNoticeAt.remove(job.jobId());
                continue;
            }
            if (job.state() == JobState.PAUSED_BODY || job.state() == JobState.PAUSED_OWNER) {
                resumeEligible(server, job.citizenId());
            } else if (job.state() == JobState.PAUSED_BRAIN) {
                maybeRetryBrain(server, job, now);
            } else if (job.state() == JobState.NEEDS_INPUT) {
                maybeAutoResume(server, job, now);
            } else if (job.state() == JobState.WAITING_ACTION) {
                maybeTimeOutAction(server, job, now);
            } else if (job.state() == JobState.QUEUED
                    && !CitizenJobData.isWaitingInLine(job)
                    && !inFlight.containsKey(job.jobId())) {
                // A promoted or crash-orphaned initial start with no driver:
                // resume() routes it through the idempotent /start retry once
                // the citizen, owner, and body are available.
                resume(server, job.jobId(), "");
            }
        }
        promoteQueuedJobs(server, now);
    }

    /**
     * Cancels and fails a physical action whose Numen completion never arrived.
     * The synthesized failure reaches the brain as a machine-readable timeout so
     * the planner re-plans immediately instead of the job silently sitting in
     * WAITING_ACTION until its multi-hour active-time budget is gone.
     */
    private void maybeTimeOutAction(MinecraftServer server, JobRecord job, long now) {
        PendingAction pending = job.pendingAction().orElse(null);
        if (pending == null || pending.resultJson().isPresent()) {
            return;
        }
        long startedAt = actionDispatchedAt.computeIfAbsent(job.jobId(), id -> now);
        long timeout = actionTimeoutTicks(pending.toolName());
        if (now - startedAt < timeout) {
            return;
        }
        actionDispatchedAt.remove(job.jobId());
        NumenToolGateway.cancelExecution(pending.executionId());
        CitizenRecord citizenRecord = CitizenRegistryData.get(server)
                .findByCitizenId(job.citizenId()).orElse(null);
        if (citizenRecord != null) {
            NumenPlayer body = NumenServerCompat.findLiveManaged(
                    server, citizenRecord.citizenId(), citizenRecord.bodyOwnerId());
            if (body != null) {
                NumenToolGateway.cancelBody(body);
            }
        }
        ZapeGCitizens.LOGGER.warn(
                "[citizen-job] action watchdog canceled job={} citizen={} tool={} after {} ticks",
                job.jobId(), job.citizenId(), pending.toolName(), now - startedAt);
        tellActor(server, job, "[" + citizenName(server, job.citizenId())
                + "] Bu adım çok uzun sürdü (" + pending.toolName()
                + "); iptal edip yeniden planlıyorum.");
        // The Turkish line above covers this failure; suppress the generic one.
        lastFailureNoticeAt.put(job.jobId(), now);
        onToolResult(server, job.jobId(), pending.actionId(),
                actionTimeoutResult(pending.toolName(), timeout));
    }

    /**
     * Starts the oldest waiting-in-line job of each citizen whose driving job
     * has ended — or is parked by an exhausted budget ({@code PAUSED_BUDGET}
     * counts as terminal for scheduling; the parked row stays visible until
     * canceled). Promotion only rewrites the waiting marker; the regular
     * QUEUED initial-start path performs the availability checks and the HTTP
     * start.
     */
    private void promoteQueuedJobs(MinecraftServer server, long now) {
        CitizenJobData data = CitizenJobData.get(server);
        Set<UUID> promotedCitizens = new TreeSet<>();
        for (JobRecord job : data.all()) {
            if (!CitizenJobData.isWaitingInLine(job)) {
                continue;
            }
            UUID citizenId = job.citizenId();
            if (promotedCitizens.contains(citizenId)
                    || data.activeDrivingForCitizen(citizenId).isPresent()) {
                continue;
            }
            promotedCitizens.add(citizenId);
            JobRecord promoted = data.update(job.jobId(), current -> current.transition(
                    JobState.QUEUED,
                    JobProgress.queued(),
                    current.actionsCompleted(),
                    current.activeTicks(),
                    current.pendingAction(),
                    current.lastConfirmedActionId(),
                    Optional.empty(),
                    now)).orElseThrow();
            ZapeGCitizens.LOGGER.info(
                    "[citizen-job] promoting queued job={} citizen={}",
                    promoted.jobId(), citizenId);
            JobOperation operation = resume(server, promoted.jobId(), "");
            if (operation.successful()) {
                tellActor(server, promoted, "[" + citizenName(server, citizenId)
                        + "] Sıradaki göreve başlıyorum: " + goalSnippet(promoted.goal()));
            }
        }
    }

    /**
     * A job paused for a requirement never notices on its own that the requirement was
     * supplied. While the job sits in NEEDS_INPUT, watch the citizen body's inventory and,
     * when its contents change, resume once with a server-authenticated description of the
     * change as the answer so the planner can verify and continue. Debounced and bounded:
     * after a few unanswered attempts the job waits for a manual answer instead.
     */
    private void maybeAutoResume(MinecraftServer server, JobRecord job, long now) {
        if (http == null || inFlight.containsKey(job.jobId())) {
            return;
        }
        int attempts = autoResumeBudget.attempts(job.jobId());
        if (!shouldAttemptAutoResume(attempts)) {
            if (autoResumeExhaustedNotified.add(job.jobId())) {
                tellActor(server, job, "[Citizens] " + citizenName(server, job.citizenId())
                        + " hâlâ bir cevap bekliyor (görev " + shortId(job.jobId())
                        + "): " + job.message().orElse("gereksinim karşılanmadı")
                        + " Şöyle cevapla: " + answerHint(server, job) + ".");
            }
            return;
        }
        if (now < nextAutoResumeAt.getOrDefault(job.jobId(), 0L)) {
            return;
        }
        CitizenRecord citizenRecord = CitizenRegistryData.get(server)
                .findByCitizenId(job.citizenId()).orElse(null);
        if (citizenRecord == null || !canRun(server, citizenRecord)) {
            return;
        }
        NumenPlayer body = NumenServerCompat.findLiveManaged(
                server, citizenRecord.citizenId(), citizenRecord.bodyOwnerId());
        if (body == null) {
            return;
        }
        Map<String, Integer> current = inventorySnapshot(body);
        Map<String, Integer> baseline = needsInputInventoryBaseline.get(job.jobId());
        if (baseline == null) {
            needsInputInventoryBaseline.put(job.jobId(), current);
            return;
        }
        if (baseline.equals(current)) {
            return;
        }
        needsInputInventoryBaseline.put(job.jobId(), current);
        nextAutoResumeAt.put(job.jobId(), now + AUTO_RESUME_DEBOUNCE_TICKS);
        autoResumeBudget.recordAttempt(job.jobId());
        String change = describeInventoryChange(baseline, current);
        String answer = "The citizen's inventory changed while paused: " + change + ".";
        if (HarvestPolicy.isOptionalHarvestToolRequest(job.goal(), job.message().orElse(""))
                || HarvestPolicy.describesAxeSupply(change)) {
            answer = answer + " " + HarvestPolicy.handHarvestAnswer();
        }
        ZapeGCitizens.LOGGER.info(
                "[citizen-job] auto-resume attempt job={} attempt={} change={}",
                job.jobId(), attempts + 1, change);
        JobOperation operation = resume(server, job.jobId(), answer);
        if (operation.successful()) {
            tellActor(server, job, "[" + citizenName(server, job.citizenId())
                    + "] Yeni malzeme geldi (" + change + ") — devam ediyorum.");
        } else {
            ZapeGCitizens.LOGGER.debug(
                    "[citizen-job] auto-resume deferred job={} reason={}",
                    job.jobId(), operation.message());
        }
    }

    private static String answerHint(MinecraftServer server, JobRecord job) {
        String name = citizenName(server, job.citizenId());
        boolean serverOwned = CitizenRegistryData.get(server).findByCitizenId(job.citizenId())
                .map(record -> record.logicalOwner().kind() == OwnerKind.SERVER)
                .orElse(false);
        return serverOwned
                ? "/citizen resume " + name + " <answer>"
                : "@" + name + " answer <your answer>";
    }

    /** Item counts by registry name across the whole body inventory; NBT is ignored. */
    private static Map<String, Integer> inventorySnapshot(NumenPlayer body) {
        Map<String, Integer> counts = new TreeMap<>();
        Inventory inventory = body.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(),
                    Integer::sum);
        }
        return counts;
    }

    /** Bounded signed diff of two inventory snapshots, e.g. "+1x minecraft:iron_axe". */
    static String describeInventoryChange(
            Map<String, Integer> baseline, Map<String, Integer> current) {
        Set<String> keys = new TreeSet<>(baseline.keySet());
        keys.addAll(current.keySet());
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            int delta = current.getOrDefault(key, 0) - baseline.getOrDefault(key, 0);
            if (delta == 0) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(delta > 0 ? "+" : "").append(delta).append("x ").append(key);
            if (text.length() >= MAX_INVENTORY_DIFF_LENGTH) {
                text.append("...");
                break;
            }
        }
        return text.length() == 0 ? "items moved" : text.toString();
    }

    static boolean shouldAttemptAutoResume(int attempts) {
        return attempts >= 0 && attempts < AUTO_RESUME_MAX_ATTEMPTS;
    }

    /**
     * Re-drives a job whose brain round-trip failed, with bounded exponential backoff. A
     * transient brain restart then recovers on its own; once the attempts are exhausted the job
     * stays paused and the earlier pause notification tells the actor how to resume by hand.
     */
    private void maybeRetryBrain(MinecraftServer server, JobRecord job, long now) {
        if (http == null || inFlight.containsKey(job.jobId())) {
            return;
        }
        if (now < nextBrainRetryAt.getOrDefault(job.jobId(), 0L)) {
            return;
        }
        int attempts = brainRetryAttempts.getOrDefault(job.jobId(), 0);
        if (!shouldRetryBrain(attempts)) {
            return;
        }
        brainRetryAttempts.put(job.jobId(), attempts + 1);
        nextBrainRetryAt.put(job.jobId(), now + brainRetryDelayTicks(attempts));
        ZapeGCitizens.LOGGER.info(
                "[citizen-job] retrying brain contact job={} attempt={}",
                job.jobId(), attempts + 1);
        JobOperation operation = resume(server, job.jobId(), "");
        if (!operation.successful()) {
            ZapeGCitizens.LOGGER.debug(
                    "[citizen-job] brain retry deferred job={} reason={}",
                    job.jobId(), operation.message());
        }
    }

    /** Persist a resumable pause before Numen bodies are hibernated. */
    public void shutdown(MinecraftServer server) {
        for (JobRecord job : CitizenJobData.get(server).all()) {
            if (CitizenJobData.isWaitingInLine(job)) {
                // Waiting rows persist as-is and are promoted after restart.
                continue;
            }
            if (job.state().consumesActiveTime() || job.state() == JobState.QUEUED) {
                pauseLocal(
                        server,
                        job,
                        "server stopping",
                        true,
                        JobState.PAUSED_SHUTDOWN);
            }
        }
        inFlight.clear();
        cancelInFlight.clear();
        nextCancelRetryAt.clear();
        cancelRetryAttempts.clear();
        nextBrainRetryAt.clear();
        brainRetryAttempts.clear();
        brainPauseNotified.clear();
        needsInputInventoryBaseline.clear();
        nextAutoResumeAt.clear();
        autoResumeBudget.clearAll();
        autoResumeExhaustedNotified.clear();
        actionDispatchedAt.clear();
        lastFailureNoticeAt.clear();
        attachedServer = null;
    }

    public String formatStatus(JobRecord job) {
        String budget = job.actionsCompleted() + "/" + job.budget().maxActions()
                + " actions, " + (job.activeTicks() / 20L) + "/"
                + job.budget().maxActiveSeconds() + " active seconds";
        String detail = job.message()
                .filter(message -> !message.isBlank())
                .map(message -> " — " + message)
                .orElse("");
        return "Job " + shortId(job.jobId()) + " "
                + job.state().name().toLowerCase(Locale.ROOT) + " - "
                + job.progress().phase() + ": " + job.progress().summary()
                + detail + " (" + budget + ")";
    }

    private void startRemote(
            MinecraftServer server,
            JobRecord job,
            CitizenRecord citizenRecord,
            boolean resumeAfterAcknowledgedPause) {
        long now = server.overworld().getGameTime();
        JobRecord running = CitizenJobData.get(server).update(job.jobId(), current ->
                current.transition(
                        JobState.RUNNING,
                        current.progress(),
                        current.actionsCompleted(),
                        current.activeTicks(),
                        current.pendingAction(),
                        current.lastConfirmedActionId(),
                        Optional.empty(),
                        now)).orElseThrow();
        CitizenIdentity identity = identity(citizenRecord);
        JsonArray tools = NumenToolGateway.toolDefinitions();
        beginRemote(
                server,
                job.jobId(),
                () -> http.startJob(running.startRequestId(), running, identity, tools),
                null,
                null,
                resumeAfterAcknowledgedPause);
    }

    private void reportStoredResult(
            MinecraftServer server, JobRecord job, boolean resumeAfterAcknowledgedPause) {
        PendingAction action = job.pendingAction().orElse(null);
        String result = action == null ? null : action.resultJson().orElse(null);
        if (action == null || result == null) {
            pauseLocal(server, job, "stored action result is unavailable", false);
            return;
        }
        UUID requestId = operationRequestId(
                job.jobId(), "result:" + action.actionId());
        beginRemote(
                server,
                job.jobId(),
                () -> http.reportJobResult(
                        requestId, job.jobId(), action.actionId(), result),
                action.actionId(),
                null,
                resumeAfterAcknowledgedPause);
    }

    private void beginRemote(
            MinecraftServer server,
            UUID jobId,
            Supplier<CompletableFuture<JobReply>> operation,
            String acknowledgedActionId,
            String interruptedActionId,
            boolean resumeAfterAcknowledgedPause) {
        UUID generation = UUID.randomUUID();
        if (inFlight.putIfAbsent(jobId, generation) != null) {
            return;
        }
        try {
            operation.get().whenComplete((reply, error) -> server.execute(() -> {
                if (!inFlight.remove(jobId, generation)) {
                    ZapeGCitizens.LOGGER.debug(
                            "[citizen-job] ignored stale HTTP reply job={}", jobId);
                    return;
                }
                handleReply(
                        server,
                        jobId,
                        acknowledgedActionId,
                        interruptedActionId,
                        resumeAfterAcknowledgedPause,
                        reply,
                        error);
            }));
        } catch (RuntimeException exception) {
            if (inFlight.remove(jobId, generation)) {
                server.execute(() -> handleReply(
                        server,
                        jobId,
                        acknowledgedActionId,
                        interruptedActionId,
                        resumeAfterAcknowledgedPause,
                        null,
                        exception));
            }
        }
    }

    private void handleReply(
            MinecraftServer server,
            UUID jobId,
            String acknowledgedActionId,
            String interruptedActionId,
            boolean resumeAfterAcknowledgedPause,
            JobReply reply,
            Throwable error) {
        JobRecord job = CitizenJobData.get(server).find(jobId).orElse(null);
        if (job == null || job.state().terminal()) {
            return;
        }

        if (error != null) {
            if (isStillPlanningError(error)) {
                // 409 job_in_progress is the healthy signature of a long
                // planning pass (the brain answered!), not an outage: refund
                // the bounded retry attempt this probe consumed and park the
                // job back in the silent planning-continuation wait — no
                // "brain unreachable" announcement.
                brainRetryAttempts.computeIfPresent(
                        jobId, (id, value) -> value > 1 ? value - 1 : null);
                pauseLocal(
                        server,
                        job,
                        PLANNING_IN_PROGRESS_PREFIX
                                + ": the brain is still planning this job",
                        false,
                        JobState.PAUSED_BRAIN);
                return;
            }
            pauseLocal(server, job, friendlyError(error), false, JobState.PAUSED_BRAIN);
            return;
        }
        if (reply == null || !reply.jobId().equals(jobId)) {
            pauseLocal(server, job, "the brain returned a mismatched job identity", false);
            return;
        }
        // A valid reply proves the brain is reachable again; reset the PAUSED_BRAIN
        // backoff. A provider_unavailable pause is the one valid reply that still
        // reports an outage: keep its attempt count so a long outage stays bounded
        // instead of retrying every backoff floor until the model-call budget dies.
        boolean providerOutage = reply.kind() == JobReplyKind.PAUSED
                && reply.reason().map(CitizenJobManager::isProviderUnavailableReason)
                        .orElse(false);
        if (!providerOutage) {
            nextBrainRetryAt.remove(jobId);
            brainRetryAttempts.remove(jobId);
            brainPauseNotified.remove(jobId);
        }

        if (acknowledgedActionId != null) {
            PendingAction pending = job.pendingAction().orElse(null);
            if (pending == null || !pending.actionId().equals(acknowledgedActionId)
                    || pending.resultJson().isEmpty()) {
                pauseLocal(server, job, "the brain acknowledged an unexpected action result", false);
                return;
            }
            long now = server.overworld().getGameTime();
            job = CitizenJobData.get(server).update(jobId, current -> current.transition(
                    JobState.RUNNING,
                    current.progress(),
                    current.actionsCompleted(),
                    current.activeTicks(),
                    Optional.empty(),
                    current.lastConfirmedActionId(),
                    Optional.empty(),
                    now)).orElseThrow();
        }

        if (interruptedActionId != null) {
            PendingAction pending = job.pendingAction().orElse(null);
            if (pending == null || !pending.actionId().equals(interruptedActionId)
                    || pending.resultJson().isPresent() || !pending.uncertain()) {
                pauseLocal(server, job, "the brain resumed an unexpected interrupted action", false);
                return;
            }
            long now = server.overworld().getGameTime();
            job = CitizenJobData.get(server).update(jobId, current -> current.transition(
                    JobState.RUNNING,
                    current.progress(),
                    current.actionsCompleted(),
                    current.activeTicks(),
                    Optional.empty(),
                    current.lastConfirmedActionId(),
                    Optional.empty(),
                    now)).orElseThrow();
        }

        if (reply.progress().actionsLimit() != job.budget().maxActions()
                || reply.progress().actionsCompleted() != job.actionsCompleted()) {
            pauseLocal(server, job, "the brain returned inconsistent action-budget progress", false);
            return;
        }
        JobProgress progress = new JobProgress(
                reply.progress().phase(), reply.progress().summary());

        switch (reply.kind()) {
            case ACTION -> dispatchAction(server, job, progress, reply.action().orElseThrow());
            case NEEDS_INPUT -> {
                String question = reply.question().orElseThrow();
                boolean optionalTool = HarvestPolicy.isOptionalHarvestToolRequest(
                        job.goal(), question);
                boolean sequenceReissue = InstructionPolicy.isSequenceReissueRequest(
                        job.goal(), question);
                needsInput(server, job, progress, question, !(optionalTool || sequenceReissue));
                if (optionalTool) {
                    JobOperation operation = resume(
                            server, job.jobId(), HarvestPolicy.handHarvestAnswer());
                    if (!operation.successful()) {
                        tellActor(server, job, "[Citizens] " + operation.message());
                    }
                } else if (sequenceReissue) {
                    JobOperation operation = resume(
                            server,
                            job.jobId(),
                            InstructionPolicy.continueOriginalInstructionAnswer());
                    if (!operation.successful()) {
                        tellActor(server, job, "[Citizens] " + operation.message());
                    }
                }
            }
            case COMPLETED -> complete(
                    server, job, progress, reply.speech().orElseThrow());
            case PAUSED -> {
                String reason = reply.reason().orElseThrow();
                if (isTransientBrainPauseReason(reason)) {
                    // Provider outages and long multi-pass planning are
                    // retryable: PAUSED_BRAIN's bounded backoff re-drives the
                    // job instead of waiting for a manual resume (and instead
                    // of an immediate resume loop during pause recovery).
                    pauseLocal(server, job, reason, false, progress,
                            JobState.PAUSED_BRAIN);
                } else if (isBudgetExhaustedReason(reason)) {
                    // The sidecar's budget stops mirror the mod's own
                    // enforcement: park the job as PAUSED_BUDGET so the queue
                    // promotes past it and the advice says "cancel", never a
                    // resume that would just re-pause with the same reason.
                    pauseLocal(server, job, reason, false, progress,
                            JobState.PAUSED_BUDGET);
                } else if (resumeAfterAcknowledgedPause) {
                    long now = server.overworld().getGameTime();
                    CitizenJobData.get(server).update(job.jobId(), current -> current.transition(
                            JobState.PAUSED,
                            progress,
                            current.actionsCompleted(),
                            current.activeTicks(),
                            current.pendingAction(),
                            current.lastConfirmedActionId(),
                            reply.reason(),
                            now));
                    JobOperation operation = resume(server, job.jobId(), "");
                    if (!operation.successful()) {
                        tellActor(server, job, "[Citizens] " + operation.message());
                    }
                } else {
                    pauseLocal(server, job, reason, false, progress);
                }
            }
        }
    }

    private void dispatchAction(
            MinecraftServer server, JobRecord job, JobProgress progress, JobAction action) {
        if (job.pendingAction().isPresent()) {
            pauseLocal(server, job, "the brain issued an action while another was pending", false);
            return;
        }
        if (job.actionsCompleted() >= job.budget().maxActions()) {
            pauseLocal(
                    server,
                    job,
                    "action budget exhausted",
                    false,
                    progress,
                    JobState.PAUSED_BUDGET);
            return;
        }
        CitizenRecord citizenRecord = CitizenRegistryData.get(server)
                .findByCitizenId(job.citizenId()).orElse(null);
        if (citizenRecord == null) {
            failLocal(server, job, "the managed citizen no longer exists");
            return;
        }
        if (!canRun(server, citizenRecord)) {
            pauseLocal(
                    server,
                    job,
                    "waiting for the citizen's player owner",
                    false,
                    progress,
                    JobState.PAUSED_OWNER);
            return;
        }
        NumenPlayer body = NumenServerCompat.findLiveManaged(
                server, citizenRecord.citizenId(), citizenRecord.bodyOwnerId());
        if (body == null) {
            pauseLocal(
                    server,
                    job,
                    "the citizen body is unavailable",
                    false,
                    progress,
                    JobState.PAUSED_BODY);
            return;
        }

        UUID actionExecutionId = UUID.nameUUIDFromBytes(
                ("zapeg-job-action:" + job.jobId() + ":" + action.id())
                        .getBytes(StandardCharsets.UTF_8));
        String executionId = "mcp-zapeg-job-" + actionExecutionId;
        PendingAction pending = new PendingAction(
                action.id(),
                executionId,
                action.name(),
                action.arguments().toString(),
                READ_ONLY_TOOLS.contains(action.name()),
                false,
                Optional.empty());
        long now = server.overworld().getGameTime();
        JobRecord waiting = CitizenJobData.get(server).update(job.jobId(), current -> current.transition(
                JobState.WAITING_ACTION,
                progress,
                current.actionsCompleted(),
                current.activeTicks(),
                Optional.of(pending),
                current.lastConfirmedActionId(),
                Optional.empty(),
                now)).orElseThrow();
        if (!flushLedger(server, waiting, "pending action")) {
            pauseLocal(
                    server,
                    waiting,
                    "the pending action could not be durably saved",
                    false,
                    JobState.PAUSED_BRAIN);
            return;
        }

        ZapeGCitizens.LOGGER.info(
                "[citizen-job] action job={} citizen={} index={} tool={} read_only={}",
                job.jobId(), job.citizenId(), job.actionsCompleted() + 1,
                action.name(), pending.readOnly());
        // Only a MUTATING action is real progress past the last declared
        // requirement. The mandated read-only verification after every
        // auto-resume answer must not re-arm the bounded budget, or a junk-item
        // drip would reset the counter every resume→verify→needs_input cycle
        // and burn the whole job budget with zero work.
        if (autoResumeBudget.actionDispatched(job.jobId(), pending.readOnly())) {
            autoResumeExhaustedNotified.remove(job.jobId());
            nextAutoResumeAt.remove(job.jobId());
        }
        // A crash-recovered action keeps its deterministic execution id; drop any
        // stale registration so the re-dispatch cannot fail as a duplicate.
        NumenToolGateway.cancelExecution(executionId);
        actionDispatchedAt.put(job.jobId(), now);
        NumenToolGateway.execute(
                body,
                executionId,
                action.name(),
                action.arguments(),
                result -> onToolResult(server, job.jobId(), action.id(), result));
    }

    private void onToolResult(
            MinecraftServer server, UUID jobId, String actionId, String rawResult) {
        server.execute(() -> {
            JobRecord job = CitizenJobData.get(server).find(jobId).orElse(null);
            if (job == null || job.state() != JobState.WAITING_ACTION) {
                return;
            }
            PendingAction pending = job.pendingAction().orElse(null);
            if (pending == null || !pending.actionId().equals(actionId)
                    || pending.resultJson().isPresent()) {
                return;
            }
            String result = boundedResult(rawResult);
            long now = server.overworld().getGameTime();
            actionDispatchedAt.remove(jobId);
            JobRecord reporting = CitizenJobData.get(server).update(jobId, current ->
                    current.transition(
                            JobState.REPORTING_RESULT,
                            current.progress(),
                            current.actionsCompleted() + 1,
                            current.activeTicks(),
                            Optional.of(pending.withResult(result)),
                            Optional.of(actionId),
                            Optional.empty(),
                            now)).orElseThrow();
            if (!flushLedger(server, reporting, "action result")) {
                pauseLocal(
                        server,
                        reporting,
                        "the action result could not be durably saved",
                        false,
                        JobState.PAUSED_BRAIN);
                return;
            }
            maybeAnnounceFailure(server, reporting, pending.toolName(), result, now);
            reportStoredResult(server, reporting, false);
        });
    }

    /**
     * One throttled line from the citizen itself whenever a physical step fails,
     * so replanning is visible in chat instead of a silent stall. The brain
     * separately receives the full machine-readable failure result.
     */
    private void maybeAnnounceFailure(
            MinecraftServer server, JobRecord job, String toolName, String resultJson, long now) {
        String message = failureMessage(resultJson);
        if (message == null) {
            return;
        }
        long previous = lastFailureNoticeAt.getOrDefault(job.jobId(), Long.MIN_VALUE / 2);
        if (now - previous < FAILURE_NOTICE_MIN_INTERVAL_TICKS) {
            return;
        }
        lastFailureNoticeAt.put(job.jobId(), now);
        tellActor(server, job, "[" + citizenName(server, job.citizenId())
                + "] Adım başarısız (" + toolName + "): " + message
                + " — yeniden planlıyorum.");
    }

    private void needsInput(
            MinecraftServer server, JobRecord job, JobProgress progress, String question) {
        needsInput(server, job, progress, question, true);
    }

    private void needsInput(
            MinecraftServer server,
            JobRecord job,
            JobProgress progress,
            String question,
            boolean announce) {
        long now = server.overworld().getGameTime();
        CitizenJobData.get(server).update(job.jobId(), current -> current.transition(
                JobState.NEEDS_INPUT,
                progress,
                current.actionsCompleted(),
                current.activeTicks(),
                Optional.empty(),
                current.lastConfirmedActionId(),
                Optional.of(question),
                now));
        // A DISTINCT new requirement legitimately re-arms the bounded auto-resume
        // budget; the same question re-declared after a verification pass keeps
        // its counter so the 4-attempt cap actually binds per requirement.
        if (autoResumeBudget.declareRequirement(job.jobId(), question)) {
            autoResumeExhaustedNotified.remove(job.jobId());
            nextAutoResumeAt.remove(job.jobId());
        }
        // Re-baseline the inventory watch so the auto-resume reacts to what arrives
        // after this requirement was declared, not to earlier contents.
        CitizenRecord citizenRecord = CitizenRegistryData.get(server)
                .findByCitizenId(job.citizenId()).orElse(null);
        NumenPlayer body = citizenRecord == null
                ? null
                : NumenServerCompat.findLiveManaged(
                        server, citizenRecord.citizenId(), citizenRecord.bodyOwnerId());
        if (body != null) {
            needsInputInventoryBaseline.put(job.jobId(), inventorySnapshot(body));
        } else {
            needsInputInventoryBaseline.remove(job.jobId());
        }
        if (announce) {
            // The citizen speaks for itself when it needs something, matching completion speech.
            tellActor(server, job, "[" + citizenName(server, job.citizenId()) + "] " + question);
        }
    }

    private void complete(
            MinecraftServer server, JobRecord job, JobProgress progress, String speech) {
        long now = server.overworld().getGameTime();
        CitizenJobData.get(server).update(job.jobId(), current -> current.transition(
                JobState.COMPLETED,
                progress,
                current.actionsCompleted(),
                current.activeTicks(),
                Optional.empty(),
                current.lastConfirmedActionId(),
                Optional.of(speech),
                now));
        tellActor(server, job, "[" + citizenName(server, job.citizenId()) + "] " + speech);
        ZapeGCitizens.LOGGER.info(
                "[citizen-job] completed job={} citizen={} actions={}",
                job.jobId(), job.citizenId(), job.actionsCompleted());
    }

    private void pauseLocal(
            MinecraftServer server, JobRecord job, String reason, boolean cancelPhysical) {
        pauseLocal(server, job, reason, cancelPhysical, job.progress(), JobState.PAUSED);
    }

    private void pauseLocal(
            MinecraftServer server,
            JobRecord job,
            String reason,
            boolean cancelPhysical,
            JobState pauseState) {
        pauseLocal(server, job, reason, cancelPhysical, job.progress(), pauseState);
    }

    private void pauseLocal(
            MinecraftServer server,
            JobRecord job,
            String requestedReason,
            boolean cancelPhysical,
            JobProgress progress) {
        pauseLocal(server, job, requestedReason, cancelPhysical, progress, JobState.PAUSED);
    }

    private void pauseLocal(
            MinecraftServer server,
            JobRecord job,
            String requestedReason,
            boolean cancelPhysical,
            JobProgress progress,
            JobState pauseState) {
        if (job.state().terminal()) {
            return;
        }
        if (job.state() == JobState.CANCELING) {
            continueCancellation(server, job, null, "pause encountered cancellation");
            return;
        }
        inFlight.remove(job.jobId());
        if (pauseState != JobState.PAUSED
                && pauseState != JobState.PAUSED_BODY
                && pauseState != JobState.PAUSED_OWNER
                && pauseState != JobState.PAUSED_BRAIN
                && pauseState != JobState.PAUSED_BUDGET
                && pauseState != JobState.PAUSED_SHUTDOWN) {
            throw new IllegalArgumentException("pauseState must be a paused job state");
        }
        String reason = boundedReason(requestedReason, "job paused");
        long now = server.overworld().getGameTime();
        JobRecord paused = CitizenJobData.get(server).update(job.jobId(), current -> {
            Optional<PendingAction> pending = current.pendingAction().map(action ->
                    action.resultJson().isPresent() ? action : action.markUncertain());
            return current.transition(
                    pauseState,
                    progress,
                    current.actionsCompleted(),
                    current.activeTicks(),
                    pending,
                    current.lastConfirmedActionId(),
                    Optional.of(reason),
                    now);
        }).orElseThrow();
        if (cancelPhysical) {
            CitizenRecord citizenRecord = CitizenRegistryData.get(server)
                    .findByCitizenId(job.citizenId()).orElse(null);
            if (citizenRecord != null) {
                cancelPhysical(server, citizenRecord, paused);
            }
        }
        observeControl(http == null
                        ? null
                        : http.pauseJob(
                                UUID.randomUUID(),
                                job.jobId(),
                                reason),
                "pause", job);
        ZapeGCitizens.LOGGER.warn(
                "[citizen-job] paused job={} citizen={} reason={}",
                job.jobId(), job.citizenId(), reason);
        notifyPause(server, job, pauseState, reason);
    }

    /**
     * Tells the submitting actor why a job stopped and how to get it moving again. Only the
     * states that do not recover on their own are announced, and only on a fresh transition, so
     * routine body/owner pauses and repeated recovery passes do not spam chat.
     */
    private void notifyPause(
            MinecraftServer server, JobRecord job, JobState pauseState, String reason) {
        if (job.state() == pauseState) {
            return;
        }
        // A long planning phase intentionally cycles through PAUSED_BRAIN and back;
        // it is normal progress, never worth a chat line.
        if (pauseState == JobState.PAUSED_BRAIN && isPlanningContinuationReason(reason)) {
            return;
        }
        // A brain outage auto-retries through RUNNING and back; announce only the first
        // transition of each outage so the backoff cycles do not spam chat.
        if (pauseState == JobState.PAUSED_BRAIN && !brainPauseNotified.add(job.jobId())) {
            return;
        }
        String advice = pauseAdvice(server, job, pauseState);
        if (advice == null) {
            return;
        }
        tellActor(server, job, "[Citizens] " + citizenName(server, job.citizenId())
                + " paused job " + shortId(job.jobId()) + ": " + reason + ". " + advice);
    }

    private static String pauseAdvice(
            MinecraftServer server, JobRecord job, JobState pauseState) {
        String name = citizenName(server, job.citizenId());
        boolean serverOwned = CitizenRegistryData.get(server).findByCitizenId(job.citizenId())
                .map(record -> record.logicalOwner().kind() == OwnerKind.SERVER)
                .orElse(false);
        String resume = serverOwned ? "/citizen resume " + name : "@" + name + " resume";
        String stop = serverOwned ? "/citizen stop " + name : "@" + name + " stop";
        return switch (pauseState) {
            case PAUSED -> "Resume with " + resume + ", or cancel with " + stop + ".";
            case PAUSED_BRAIN -> "The shared brain is unreachable; retrying automatically for "
                    + "a few minutes. If this persists, check the brain, then use " + resume + ".";
            case PAUSED_BUDGET -> "The job used its configured budget and cannot resume; "
                    + "cancel it with " + stop + ".";
            default -> null;
        };
    }

    private void failLocal(MinecraftServer server, JobRecord job, String requestedReason) {
        inFlight.remove(job.jobId());
        String reason = boundedReason(requestedReason, "job failed");
        long now = server.overworld().getGameTime();
        CitizenJobData.get(server).update(job.jobId(), current -> current.transition(
                JobState.FAILED,
                current.progress(),
                current.actionsCompleted(),
                current.activeTicks(),
                current.pendingAction(),
                current.lastConfirmedActionId(),
                Optional.of(reason),
                now));
        tellActor(server, job, "[Citizens] Job " + shortId(job.jobId()) + " failed: "
                + reason + ".");
    }

    private static void cancelPhysical(
            MinecraftServer server, CitizenRecord citizenRecord, JobRecord job) {
        job.pendingAction().ifPresent(action ->
                NumenToolGateway.cancelExecution(action.executionId()));
        NumenPlayer body = NumenServerCompat.findLiveManaged(
                server, citizenRecord.citizenId(), citizenRecord.bodyOwnerId());
        if (body != null) {
            NumenToolGateway.cancelBody(body);
        }
    }

    /**
     * Replays every cancellation boundary in order: synchronous ledger save attempt, physical
     * revocation, then remote acknowledgement. This is also used after restart and by retry ticks,
     * so a prior failed save cannot accidentally skip physical cancellation forever.
     */
    private boolean continueCancellation(
            MinecraftServer server,
            JobRecord job,
            CitizenRecord knownRecord,
            String saveReason) {
        if (job.state() != JobState.CANCELING || !flushLedger(server, job, saveReason)) {
            return false;
        }
        CitizenRecord citizenRecord = knownRecord != null
                ? knownRecord
                : CitizenRegistryData.get(server)
                        .findByCitizenId(job.citizenId()).orElse(null);
        if (citizenRecord != null) {
            cancelPhysical(server, citizenRecord, job);
        } else {
            job.pendingAction().ifPresent(action ->
                    NumenToolGateway.cancelExecution(action.executionId()));
        }
        requestRemoteCancel(server, job);
        return true;
    }

    private void requestRemoteCancel(MinecraftServer server, JobRecord job) {
        if (http == null || job.state() != JobState.CANCELING) {
            return;
        }
        UUID generation = UUID.randomUUID();
        if (cancelInFlight.putIfAbsent(job.jobId(), generation) != null) {
            return;
        }
        // Bounded churn while the brain is down: exponential backoff like the
        // resume retries, then a slow five-minute cadence. CANCELING itself
        // stays durable — only the retry frequency decays.
        int attempt = cancelRetryAttempts.merge(job.jobId(), 1, Integer::sum) - 1;
        nextCancelRetryAt.put(
                job.jobId(),
                server.overworld().getGameTime() + cancelRetryDelayTicks(attempt));
        CompletableFuture<JobReply> future;
        try {
            future = http.cancelJob(
                    operationRequestId(job.jobId(), "cancel"),
                    job.jobId(),
                    job.message().orElse("job canceled"));
        } catch (RuntimeException exception) {
            cancelInFlight.remove(job.jobId(), generation);
            ZapeGCitizens.LOGGER.warn(
                    "[citizen-job] cancel request construction failed job={}",
                    job.jobId(), exception);
            return;
        }
        future.whenComplete((reply, error) -> server.execute(() -> {
            if (!cancelInFlight.remove(job.jobId(), generation)) {
                return;
            }
            JobRecord current = CitizenJobData.get(server).find(job.jobId()).orElse(null);
            if (current == null || current.state() != JobState.CANCELING) {
                return;
            }
            if (error != null || reply == null || !reply.jobId().equals(job.jobId())) {
                ZapeGCitizens.LOGGER.warn(
                        "[citizen-job] remote cancel remains pending job={} error_type={}",
                        job.jobId(),
                        error == null ? "invalid_reply" : error.getClass().getSimpleName());
                return;
            }
            if (reply.kind() == JobReplyKind.COMPLETED) {
                if (reply.progress().actionsLimit() != current.budget().maxActions()
                        || reply.progress().actionsCompleted() != current.actionsCompleted()) {
                    ZapeGCitizens.LOGGER.warn(
                            "[citizen-job] completed cancel acknowledgement has inconsistent progress job={}",
                            job.jobId());
                    return;
                }
                JobProgress progress = new JobProgress(
                        reply.progress().phase(), reply.progress().summary());
                complete(server, current, progress, reply.speech().orElseThrow());
                nextCancelRetryAt.remove(job.jobId());
                cancelRetryAttempts.remove(job.jobId());
                return;
            }
            if (reply.kind() != JobReplyKind.PAUSED) {
                ZapeGCitizens.LOGGER.warn(
                        "[citizen-job] remote cancel returned unexpected kind job={} kind={}",
                        job.jobId(), reply.kind());
                return;
            }
            long now = server.overworld().getGameTime();
            JobRecord canceled = CitizenJobData.get(server).update(job.jobId(), value ->
                    value.transition(
                            JobState.CANCELED,
                            value.progress(),
                            value.actionsCompleted(),
                            value.activeTicks(),
                            Optional.empty(),
                            value.lastConfirmedActionId(),
                            value.message(),
                            now)).orElseThrow();
            flushLedger(server, canceled, "cancellation acknowledgement");
            nextCancelRetryAt.remove(job.jobId());
            cancelRetryAttempts.remove(job.jobId());
            tellActor(server, canceled,
                    "[Citizens] Job " + shortId(job.jobId()) + " is canceled.");
        }));
    }

    private static boolean canRun(MinecraftServer server, CitizenRecord citizenRecord) {
        if (citizenRecord.logicalOwner().kind() == OwnerKind.SERVER) {
            return true;
        }
        return citizenRecord.logicalOwner().playerId()
                .map(server.getPlayerList()::getPlayer)
                .isPresent();
    }

    private static CitizenIdentity identity(CitizenRecord record) {
        return new CitizenIdentity(
                record.citizenId(),
                record.name(),
                record.logicalOwner().kind().name(),
                record.logicalOwner().id(),
                record.role(),
                record.faction(),
                record.persona(),
                CitizenBrainCoordinator.InteractionMode.TASK.name());
    }

    private void attach(MinecraftServer server) {
        if (attachedServer != server) {
            attachedServer = server;
            lastBudgetAccountAt = server.overworld().getGameTime();
            inFlight.clear();
            cancelInFlight.clear();
            nextCancelRetryAt.clear();
            cancelRetryAttempts.clear();
            nextBrainRetryAt.clear();
            brainRetryAttempts.clear();
            brainPauseNotified.clear();
            needsInputInventoryBaseline.clear();
            nextAutoResumeAt.clear();
            autoResumeBudget.clearAll();
            autoResumeExhaustedNotified.clear();
            actionDispatchedAt.clear();
            lastFailureNoticeAt.clear();
        }
    }

    private static void tellActor(MinecraftServer server, JobRecord job, String text) {
        ServerPlayer actor = server.getPlayerList().getPlayer(job.actorId());
        if (actor != null) {
            actor.sendSystemMessage(Component.literal(text));
        }
    }

    private static String citizenName(MinecraftServer server, UUID citizenId) {
        return CitizenRegistryData.get(server).findByCitizenId(citizenId)
                .map(CitizenRecord::name)
                .orElse("Citizen");
    }

    /**
     * Flushes the recovery-critical SavedData boundary before starting a remote plan or a
     * physical action. This is intentionally narrower than a full world/chunk save.
     */
    private static boolean flushLedger(
            MinecraftServer server, JobRecord job, String boundary) {
        try {
            server.overworld().getDataStorage().save();
            return true;
        } catch (RuntimeException exception) {
            ZapeGCitizens.LOGGER.error(
                    "[citizen-job] failed to flush ledger job={} citizen={} boundary={}",
                    job.jobId(), job.citizenId(), boundary, exception);
            return false;
        }
    }

    private static UUID operationRequestId(UUID jobId, String operation) {
        return UUID.nameUUIDFromBytes(
                ("zapeg-job:" + jobId + ":" + operation).getBytes(StandardCharsets.UTF_8));
    }

    static boolean isReadOnlyTool(String toolName) {
        return READ_ONLY_TOOLS.contains(toolName);
    }

    static boolean shouldRetryInitialStart(JobRecord job) {
        return job != null
                && (job.state() == JobState.QUEUED
                        || job.state() == JobState.RUNNING
                        || job.state() == JobState.PAUSED_BODY
                        || job.state() == JobState.PAUSED_OWNER
                        || job.state() == JobState.PAUSED_BRAIN
                        || job.state() == JobState.PAUSED_SHUTDOWN)
                && job.actionsCompleted() == 0
                && job.pendingAction().isEmpty()
                && job.lastConfirmedActionId().isEmpty()
                && job.progress().equals(JobProgress.queued());
    }

    static boolean isResumableState(JobState state) {
        return state == JobState.NEEDS_INPUT
                || state == JobState.PAUSED
                || state == JobState.PAUSED_BODY
                || state == JobState.PAUSED_OWNER
                || state == JobState.PAUSED_BRAIN
                || state == JobState.PAUSED_SHUTDOWN;
    }

    /**
     * True for a job orphaned mid-planning by a restart: it is still marked RUNNING with an
     * acknowledged (non-queued) plan, but it has no pending action and no in-flight reply will
     * ever arrive, and resume() refuses RUNNING. Such a job must be moved to a resumable pause.
     */
    static boolean needsPlanningRecovery(JobRecord job) {
        return job != null
                && job.state() == JobState.RUNNING
                && job.pendingAction().isEmpty()
                && !job.progress().equals(JobProgress.queued());
    }

    static boolean shouldRetryBrain(int attempts) {
        return attempts >= 0 && attempts < BRAIN_RETRY_MAX_ATTEMPTS;
    }

    /** Sidecar pause reasons that self-heal through the PAUSED_BRAIN retry loop. */
    static boolean isTransientBrainPauseReason(String reason) {
        return isProviderUnavailableReason(reason) || isPlanningContinuationReason(reason);
    }

    static boolean isProviderUnavailableReason(String reason) {
        return reason != null
                && reason.strip().toLowerCase(Locale.ROOT)
                        .startsWith(PROVIDER_UNAVAILABLE_PREFIX);
    }

    static boolean isPlanningContinuationReason(String reason) {
        return reason != null
                && reason.strip().toLowerCase(Locale.ROOT)
                        .startsWith(PLANNING_IN_PROGRESS_PREFIX);
    }

    /**
     * Sidecar pause reasons ("action/model-call/active-time budget exhausted")
     * that mirror the mod's own budget stop and must park the job as
     * PAUSED_BUDGET. A template's {@code stage_budget_exhausted} is explicitly
     * excluded: an explicit resume legitimately re-arms a stage window.
     */
    static boolean isBudgetExhaustedReason(String reason) {
        if (reason == null) {
            return false;
        }
        String normalized = reason.strip().toLowerCase(Locale.ROOT);
        return !normalized.startsWith(STAGE_BUDGET_PREFIX)
                && normalized.contains("budget exhausted");
    }

    /**
     * True when a failed brain round-trip is the healthy 409
     * {@code job_in_progress} reply — the brain answered and still holds the
     * job mid-planning. Never an outage: no announcement, no attempt burn.
     */
    static boolean isStillPlanningError(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof BrainHttpClient.BrainRequestException requestException
                && requestException.statusCode() == 409
                && JOB_IN_PROGRESS_CODE.equals(requestException.errorCode());
    }

    /** Cancel retries reuse the brain backoff, then settle at a slow cadence. */
    static long cancelRetryDelayTicks(int attempt) {
        return attempt >= BRAIN_RETRY_MAX_ATTEMPTS
                ? CANCEL_RETRY_SLOW_TICKS
                : brainRetryDelayTicks(Math.max(0, attempt));
    }

    /** Watchdog deadline for one physical action; search/build tools get longer. */
    static long actionTimeoutTicks(String toolName) {
        return LONG_RUNNING_TOOLS.contains(toolName)
                ? LONG_ACTION_TIMEOUT_TICKS
                : ACTION_TIMEOUT_TICKS;
    }

    /** Machine-readable synthesized failure for an action the watchdog canceled. */
    static String actionTimeoutResult(String toolName, long timeoutTicks) {
        return "{\"success\":false,\"code\":\"action_timeout\",\"message\":\"" + toolName
                + " did not complete within " + (timeoutTicks / 20L)
                + " seconds and was canceled by the server watchdog; the target may be"
                + " unreachable, too far, or in unloaded chunks. Choose a nearer or"
                + " different target or approach, or state the blocker with"
                + " job_needs_input\"}";
    }

    /**
     * Bounded human-readable message from an explicitly failed tool result, or
     * {@code null} for successful or unparseable content.
     */
    static String failureMessage(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            if (!root.has("success") || root.get("success").getAsBoolean()) {
                return null;
            }
            String message = root.has("message") && root.get("message").isJsonPrimitive()
                    ? root.get("message").getAsString()
                    : "";
            message = message.replace('\n', ' ').replace('\r', ' ').strip();
            if (message.isEmpty()) {
                message = "bilinmeyen hata";
            }
            int limit = Math.min(message.length(), 160);
            // Never split a surrogate pair at the truncation boundary (an emoji
            // in model text would otherwise render as one broken character).
            if (limit < message.length() && Character.isHighSurrogate(message.charAt(limit - 1))) {
                limit--;
            }
            return message.substring(0, limit);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Bounded one-line rendering of a goal for queue/promotion announcements. */
    static String goalSnippet(String goal) {
        String stripped = goal == null ? "" : goal.strip();
        if (stripped.length() <= GOAL_SNIPPET_LENGTH) {
            return stripped;
        }
        return stripped.substring(0, GOAL_SNIPPET_LENGTH - 3) + "...";
    }

    static long brainRetryDelayTicks(int attempt) {
        int shift = Math.max(0, attempt);
        long scaled = shift >= 20 ? Long.MAX_VALUE : BRAIN_RETRY_BASE_TICKS << shift;
        return Math.min(scaled, BRAIN_RETRY_MAX_TICKS);
    }

    static String boundedResult(String result) {
        String safe = result == null ? "null" : result;
        if (safe.length() <= CitizenJobData.MAX_ACTION_RESULT_LENGTH) {
            return safe;
        }
        // An oversized read-out (a huge scan) is still a completed action:
        // preserve its success flag so the queue is not discarded and the
        // citizen does not announce a failure, and tell the planner how to
        // retry the query smaller instead of pretending the step failed.
        boolean successful = false;
        try {
            JsonObject root = JsonParser.parseString(safe).getAsJsonObject();
            successful = root.has("success") && root.get("success").getAsBoolean();
        } catch (RuntimeException ignored) {
            // Unparseable oversized content stays a failure.
        }
        return "{\"success\":" + successful + ",\"truncated\":true,\"message\":\"the tool"
                + " result exceeded the durable job limit and its content was discarded;"
                + " repeat the query with a smaller radius or narrower filter if the"
                + " content is needed\"}";
    }

    private static String boundedReason(String reason, String fallback) {
        String safe = reason == null ? "" : reason.replace('\n', ' ').replace('\r', ' ').strip();
        if (safe.isEmpty()) {
            safe = fallback;
        }
        return safe.substring(0, Math.min(safe.length(), CitizenJobData.MAX_MESSAGE_LENGTH));
    }

    private static String friendlyError(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return boundedReason(message, "the shared brain is unavailable");
    }

    private static String cleanMessage(Throwable error) {
        String message = error.getMessage();
        return boundedReason(message, error.getClass().getSimpleName());
    }

    private static String shortId(UUID jobId) {
        return jobId.toString().substring(0, 8);
    }

    private static void observeControl(
            CompletableFuture<JobReply> future, String operation, JobRecord job) {
        if (future == null) {
            return;
        }
        future.exceptionally(error -> {
            ZapeGCitizens.LOGGER.warn(
                    "[citizen-job] remote {} failed job={} citizen={} error_type={}",
                    operation, job.jobId(), job.citizenId(), error.getClass().getSimpleName());
            return null;
        });
    }

    public record JobOperation(boolean successful, UUID jobId, String message) {
        public static JobOperation success(UUID jobId, String message) {
            return new JobOperation(true, jobId, message);
        }

        public static JobOperation failure(String message) {
            return new JobOperation(false, null, message);
        }
    }
}
