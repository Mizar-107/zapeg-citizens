package io.github.mizar107.zapegcitizens.lifecycle;

import com.dwinovo.numen.entity.NumenPlayer;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat.ServerBodyResult;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat.ServerBodyStatus;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.CitizenRecord;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.HomeAnchor;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.OwnerKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reconciles true server-owned Numen bodies without an online human owner.
 *
 * <p>The first release deliberately keeps every healthy server citizen awake. Numen's own
 * dispatcher already ticks ownerless bodies, while this manager supplies the two owner-gated
 * pieces: expiring chunk tickets and death recovery. All methods are intended for the Minecraft
 * server thread.
 */
public final class ServerCitizenLifecycleManager {

    public static final long DEATH_RESPAWN_DELAY_TICKS = 600L;
    public static final long POSITION_SNAPSHOT_INTERVAL_TICKS = 100L;
    // Must remain below Numen's 40-tick chunk-ticket timeout so a transient refresh failure
    // gets another attempt before the previous ticket can expire.
    public static final long FAILED_OPERATION_RETRY_TICKS = 20L;
    public static final long DEATH_RECORD_GRACE_TICKS = 100L;

    private static final double PLAYER_HALF_WIDTH = 0.3D;
    private static final double PLAYER_HEIGHT = 1.8D;
    private static final ServerCitizenLifecycleManager INSTANCE =
            new ServerCitizenLifecycleManager();

    private final Map<UUID, String> quarantined = new LinkedHashMap<>();
    private final Set<UUID> unreportedQuarantines = new HashSet<>();
    private final Map<UUID, String> lastReportedFailures = new HashMap<>();
    private final Map<UUID, Long> nextRetryAt = new HashMap<>();
    private final Map<UUID, Long> lastSnapshotAt = new HashMap<>();
    private final Map<UUID, Long> deathObservedAt = new HashMap<>();

    private MinecraftServer attachedServer;
    private boolean stopping;

    private ServerCitizenLifecycleManager() {}

    public static ServerCitizenLifecycleManager instance() {
        return INSTANCE;
    }

    /**
     * Creates the initial body under a stable technical principal.
     *
     * <p>The caller remains responsible for atomically reserving the returned body UUID in
     * {@link CitizenRegistryData}. If reservation fails, it should dismiss that exact body.
     */
    public LifecycleResult spawn(
            MinecraftServer server,
            ServerLevel level,
            Vec3 position,
            HomeAnchor home,
            String name,
            UUID bodyOwnerId) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bodyOwnerId, "bodyOwnerId");
        attach(server);
        if (stopping) {
            return LifecycleResult.failure(
                    null, LifecycleStatus.SKIPPED, "the server is stopping");
        }

        HomeResolution homeResolution = resolveHome(server, home, false);
        if (!homeResolution.successful()) {
            return LifecycleResult.failure(
                    null, LifecycleStatus.FAILED, homeResolution.detail());
        }
        if (homeResolution.level() != level) {
            return LifecycleResult.failure(
                    null,
                    LifecycleStatus.FAILED,
                    "the supplied home dimension does not match the spawn level");
        }

        ServerBodyResult result = NumenServerCompat.spawnServerBody(
                server, bodyOwnerId, name, level, position);
        LifecycleResult translated = translate(null, result);
        if (translated.successful() && translated.body() != null) {
            // The body exists now, so start the expiring Numen ticket immediately rather than
            // waiting for the next server tick.
            NumenServerCompat.refreshServerChunkTicket(
                    server, translated.body().getUUID(), bodyOwnerId);
            lastSnapshotAt.put(
                    translated.body().getUUID(), server.overworld().getGameTime());
        }
        return translated;
    }

    /** Clears transient state and reconciles every server citizen after all levels have loaded. */
    public List<LifecycleResult> start(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        resetFor(server);
        List<LifecycleResult> results = new ArrayList<>();
        for (CitizenRecord record : serverRecords(server)) {
            LifecycleResult result = wake(server, record);
            if (result.status() == LifecycleStatus.QUARANTINED) {
                // start() itself returns this transition, so a later tick must not repeat it.
                unreportedQuarantines.remove(record.citizenId());
            }
            if (result.status() == LifecycleStatus.FAILED) {
                lastReportedFailures.put(record.citizenId(), result.message());
            }
            if (result.status() != LifecycleStatus.LIVE
                    && result.status() != LifecycleStatus.WAITING) {
                results.add(result);
            }
        }
        return List.copyOf(results);
    }

    /** Wakes, refreshes, or recovers one always-awake server-owned citizen. */
    public LifecycleResult wake(MinecraftServer server, CitizenRecord record) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(record, "record");
        attach(server);
        if (record.logicalOwner().kind() != OwnerKind.SERVER) {
            return LifecycleResult.failure(
                    record.citizenId(),
                    LifecycleStatus.SKIPPED,
                    "the citizen is not server-owned");
        }
        if (stopping) {
            return LifecycleResult.failure(
                    record.citizenId(), LifecycleStatus.SKIPPED, "the server is stopping");
        }
        String quarantine = quarantined.get(record.citizenId());
        if (quarantine != null) {
            return LifecycleResult.failure(
                    record.citizenId(), LifecycleStatus.QUARANTINED, quarantine);
        }

        long now = server.overworld().getGameTime();
        Long retryAt = nextRetryAt.get(record.citizenId());
        if (retryAt != null && !retryDue(now, retryAt)) {
            return LifecycleResult.failure(
                    record.citizenId(),
                    LifecycleStatus.WAITING,
                    "the lifecycle retry delay has not elapsed");
        }

        ServerBodyResult state = NumenServerCompat.inspectServerBody(
                server, record.citizenId(), record.bodyOwnerId());
        if (state.quarantinable()) {
            return quarantine(record, state.detail());
        }

        if (deathObservedAt.containsKey(record.citizenId())
                && state.status() != ServerBodyStatus.DEAD) {
            long observedAt = deathObservedAt.get(record.citizenId());
            if (!intervalElapsed(now, observedAt, DEATH_RECORD_GRACE_TICKS)) {
                return LifecycleResult.failure(
                        record.citizenId(),
                        LifecycleStatus.WAITING,
                        "waiting for Numen to persist the observed death");
            }
            return quarantine(
                    record,
                    "Numen did not persist the observed body death within "
                            + DEATH_RECORD_GRACE_TICKS + " ticks");
        }

        return switch (state.status()) {
            case LIVE -> maintainLive(server, record, state, now);
            case DORMANT -> wakeDormant(server, record, now);
            case DEAD -> recoverDead(server, record, state, now);
            default -> handleUnexpected(record, state, now);
        };
    }

    /** Alias for callers that express the operation as an availability requirement. */
    public LifecycleResult ensureAwake(MinecraftServer server, CitizenRecord record) {
        return wake(server, record);
    }

    /**
     * Refreshes tickets on every tick and performs periodic snapshots/reconciliation.
     * Only transitions and failures are returned; healthy LIVE rows do not allocate log noise.
     */
    public List<LifecycleResult> tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        attach(server);
        if (stopping) {
            return List.of();
        }
        List<LifecycleResult> transitions = new ArrayList<>();
        for (CitizenRecord record : serverRecords(server)) {
            LifecycleResult result = wake(server, record);
            boolean newQuarantine = result.status() == LifecycleStatus.QUARANTINED
                    && unreportedQuarantines.remove(record.citizenId());
            boolean newFailure = result.status() == LifecycleStatus.FAILED
                    && !result.message().equals(
                            lastReportedFailures.put(record.citizenId(), result.message()));
            if (result.successful()) {
                lastReportedFailures.remove(record.citizenId());
            }
            if (newQuarantine
                    || newFailure
                    || (result.status() != LifecycleStatus.LIVE
                            && result.status() != LifecycleStatus.WAITING
                            && result.status() != LifecycleStatus.QUARANTINED
                            && result.status() != LifecycleStatus.FAILED)) {
                transitions.add(result);
            }
        }
        return List.copyOf(transitions);
    }

    /** Marks a real Numen death so ticket refresh cannot race its deferred death bookkeeping. */
    public LifecycleResult onDeath(MinecraftServer server, CitizenRecord record) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(record, "record");
        attach(server);
        if (record.logicalOwner().kind() != OwnerKind.SERVER) {
            return LifecycleResult.failure(
                    record.citizenId(),
                    LifecycleStatus.SKIPPED,
                    "the citizen is not server-owned");
        }
        long now = server.overworld().getGameTime();
        deathObservedAt.put(record.citizenId(), now);
        nextRetryAt.remove(record.citizenId());
        lastSnapshotAt.remove(record.citizenId());
        return LifecycleResult.failure(
                record.citizenId(),
                LifecycleStatus.WAITING,
                "the body death was observed; waiting for Numen's persistent death marker");
    }

    /**
     * Snapshots and hibernates every live server body while preserving the always-awake intent
     * for the next startup.
     */
    public List<LifecycleResult> shutdown(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        attach(server);
        stopping = true;
        List<LifecycleResult> results = new ArrayList<>();
        for (CitizenRecord record : serverRecords(server)) {
            ServerBodyResult result = NumenServerCompat.hibernateServerBody(
                    server, record.citizenId(), record.bodyOwnerId());
            if (result.quarantinable()) {
                results.add(quarantine(record, result.detail()));
            } else {
                results.add(translate(record.citizenId(), result));
            }
        }
        nextRetryAt.clear();
        lastSnapshotAt.clear();
        deathObservedAt.clear();
        return List.copyOf(results);
    }

    /** Orderly helper for one body, useful for explicit administrative hibernation. */
    public LifecycleResult hibernate(MinecraftServer server, CitizenRecord record) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(record, "record");
        if (record.logicalOwner().kind() != OwnerKind.SERVER) {
            return LifecycleResult.failure(
                    record.citizenId(),
                    LifecycleStatus.SKIPPED,
                    "the citizen is not server-owned");
        }
        ServerBodyResult result = NumenServerCompat.hibernateServerBody(
                server, record.citizenId(), record.bodyOwnerId());
        return result.quarantinable()
                ? quarantine(record, result.detail())
                : translate(record.citizenId(), result);
    }

    public Optional<String> quarantineReason(UUID citizenId) {
        return Optional.ofNullable(quarantined.get(Objects.requireNonNull(citizenId, "citizenId")));
    }

    /** Allows an operator repair path to request a fresh invariant check. */
    public boolean clearQuarantine(UUID citizenId) {
        Objects.requireNonNull(citizenId, "citizenId");
        nextRetryAt.remove(citizenId);
        deathObservedAt.remove(citizenId);
        unreportedQuarantines.remove(citizenId);
        lastReportedFailures.remove(citizenId);
        return quarantined.remove(citizenId) != null;
    }

    private LifecycleResult maintainLive(
            MinecraftServer server,
            CitizenRecord record,
            ServerBodyResult state,
            long now) {
        ServerBodyResult ticket = NumenServerCompat.refreshServerChunkTicket(
                server, record.citizenId(), record.bodyOwnerId());
        if (ticket.quarantinable()) {
            return quarantine(record, ticket.detail());
        }
        if (ticket.status() == ServerBodyStatus.FAILED) {
            return scheduleRetry(record, ticket.detail(), now);
        }

        Long lastSnapshot = lastSnapshotAt.get(record.citizenId());
        if (snapshotDue(now, lastSnapshot)) {
            ServerBodyResult snapshot = NumenServerCompat.snapshotServerPosition(
                    server, record.citizenId(), record.bodyOwnerId());
            if (snapshot.quarantinable()) {
                return quarantine(record, snapshot.detail());
            }
            if (snapshot.status() == ServerBodyStatus.FAILED) {
                return scheduleRetry(record, snapshot.detail(), now);
            }
            lastSnapshotAt.put(record.citizenId(), now);
        }
        nextRetryAt.remove(record.citizenId());
        return new LifecycleResult(
                LifecycleStatus.LIVE,
                record.citizenId(),
                state.body(),
                "the server-owned body is live and ticketed");
    }

    private LifecycleResult wakeDormant(
            MinecraftServer server, CitizenRecord record, long now) {
        ServerBodyResult result = NumenServerCompat.wakeServerBody(
                server, record.citizenId(), record.bodyOwnerId());
        if (result.quarantinable()) {
            return quarantine(record, result.detail());
        }
        if (result.status() == ServerBodyStatus.WOKEN
                || result.status() == ServerBodyStatus.LIVE) {
            ServerBodyResult ticket = NumenServerCompat.refreshServerChunkTicket(
                    server, record.citizenId(), record.bodyOwnerId());
            if (ticket.quarantinable()) {
                return quarantine(record, ticket.detail());
            }
            if (ticket.status() == ServerBodyStatus.FAILED) {
                return scheduleRetry(record, ticket.detail(), now);
            }
            nextRetryAt.remove(record.citizenId());
            lastSnapshotAt.put(record.citizenId(), now);
            return translate(record.citizenId(), result);
        }
        return scheduleRetry(record, result.detail(), now);
    }

    private LifecycleResult recoverDead(
            MinecraftServer server,
            CitizenRecord record,
            ServerBodyResult state,
            long now) {
        if (state.body() != null) {
            return LifecycleResult.failure(
                    record.citizenId(),
                    LifecycleStatus.WAITING,
                    "waiting for Numen to remove the dead body");
        }
        if (!recoveryDelayElapsed(now, state.diedAt())) {
            return LifecycleResult.failure(
                    record.citizenId(),
                    LifecycleStatus.WAITING,
                    "the 600-tick death recovery delay has not elapsed");
        }
        HomeAnchor home = record.home().orElse(null);
        if (home == null) {
            return quarantine(record, "the dead server citizen has no configured home");
        }
        HomeResolution resolution = resolveHome(server, home, true);
        if (!resolution.successful()) {
            if (resolution.permanentFailure()) {
                return quarantine(record, resolution.detail());
            }
            return scheduleRetry(record, resolution.detail(), now);
        }

        ServerBodyResult recovered = NumenServerCompat.recoverServerBody(
                server,
                record.citizenId(),
                record.bodyOwnerId(),
                record.name(),
                resolution.level(),
                resolution.position(),
                home.yaw(),
                home.pitch());
        if (recovered.quarantinable()) {
            return quarantine(record, recovered.detail());
        }
        if (recovered.status() == ServerBodyStatus.RECOVERED) {
            deathObservedAt.remove(record.citizenId());
            nextRetryAt.remove(record.citizenId());
            lastSnapshotAt.put(record.citizenId(), now);
            return translate(record.citizenId(), recovered);
        }
        return scheduleRetry(record, recovered.detail(), now);
    }

    private LifecycleResult handleUnexpected(
            CitizenRecord record, ServerBodyResult state, long now) {
        if (state.quarantinable()) {
            return quarantine(record, state.detail());
        }
        return scheduleRetry(record, state.detail(), now);
    }

    private LifecycleResult quarantine(CitizenRecord record, String detail) {
        if (quarantined.putIfAbsent(record.citizenId(), detail) == null) {
            unreportedQuarantines.add(record.citizenId());
        }
        nextRetryAt.remove(record.citizenId());
        lastSnapshotAt.remove(record.citizenId());
        deathObservedAt.remove(record.citizenId());
        return LifecycleResult.failure(
                record.citizenId(),
                LifecycleStatus.QUARANTINED,
                quarantined.get(record.citizenId()));
    }

    private LifecycleResult scheduleRetry(CitizenRecord record, String detail, long now) {
        nextRetryAt.put(record.citizenId(), now + FAILED_OPERATION_RETRY_TICKS);
        return LifecycleResult.failure(record.citizenId(), LifecycleStatus.FAILED, detail);
    }

    private static HomeResolution resolveHome(
            MinecraftServer server, HomeAnchor home, boolean requireClearBodySpace) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(home.dimension());
        if (dimensionId == null) {
            return HomeResolution.permanentFailure("the home dimension identifier is invalid");
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return HomeResolution.permanentFailure(
                    "the configured home dimension is not loaded: " + home.dimension());
        }
        Vec3 position = new Vec3(home.x(), home.y(), home.z());
        BlockPos block = BlockPos.containing(position);
        if (!level.isInWorldBounds(block)
                || !level.getWorldBorder().isWithinBounds(block)) {
            return HomeResolution.permanentFailure(
                    "the configured home is outside the world bounds");
        }
        if (requireClearBodySpace) {
            // Load the exact home chunk before collision inspection. The recovered live body
            // immediately receives Numen's normal expiring radius-two ticket.
            level.getChunkAt(block);
            AABB standingBox = new AABB(
                    position.x - PLAYER_HALF_WIDTH,
                    position.y,
                    position.z - PLAYER_HALF_WIDTH,
                    position.x + PLAYER_HALF_WIDTH,
                    position.y + PLAYER_HEIGHT,
                    position.z + PLAYER_HALF_WIDTH);
            if (!level.noCollision(standingBox)) {
                return HomeResolution.retryableFailure(
                        "the configured home is obstructed; clear it before recovery");
            }
        }
        return HomeResolution.success(level, position);
    }

    private static List<CitizenRecord> serverRecords(MinecraftServer server) {
        return CitizenRegistryData.get(server)
                .all()
                .stream()
                .filter(record -> record.logicalOwner().kind() == OwnerKind.SERVER)
                .toList();
    }

    private void attach(MinecraftServer server) {
        if (attachedServer != server) {
            resetFor(server);
        }
    }

    private void resetFor(MinecraftServer server) {
        attachedServer = server;
        stopping = false;
        quarantined.clear();
        unreportedQuarantines.clear();
        lastReportedFailures.clear();
        nextRetryAt.clear();
        lastSnapshotAt.clear();
        deathObservedAt.clear();
    }

    static boolean recoveryDelayElapsed(long now, long diedAt) {
        return diedAt > 0L && intervalElapsed(now, diedAt, DEATH_RESPAWN_DELAY_TICKS);
    }

    static boolean snapshotDue(long now, Long lastSnapshotAt) {
        return lastSnapshotAt == null
                || intervalElapsed(now, lastSnapshotAt, POSITION_SNAPSHOT_INTERVAL_TICKS);
    }

    static boolean retryDue(long now, long retryAt) {
        return now >= retryAt;
    }

    private static boolean intervalElapsed(long now, long start, long interval) {
        return now >= start && now - start >= interval;
    }

    private static LifecycleResult translate(UUID citizenId, ServerBodyResult result) {
        UUID effectiveId = citizenId;
        if (effectiveId == null && result.body() != null) {
            effectiveId = result.body().getUUID();
        }
        LifecycleStatus status = switch (result.status()) {
            case SPAWNED -> LifecycleStatus.SPAWNED;
            case LIVE -> LifecycleStatus.LIVE;
            case DORMANT -> LifecycleStatus.DORMANT;
            case DEAD -> LifecycleStatus.WAITING;
            case WOKEN -> LifecycleStatus.WOKEN;
            case RECOVERED -> LifecycleStatus.RECOVERED;
            case HIBERNATED -> LifecycleStatus.HIBERNATED;
            case MISSING, OWNER_MISMATCH, TECHNICAL_OWNER_ONLINE, BODY_ID_IN_USE ->
                    LifecycleStatus.QUARANTINED;
            case SNAPSHOTTED, UNCHANGED -> LifecycleStatus.LIVE;
            case NAME_IN_USE, NAME_ALREADY_REGISTERED, FAILED -> LifecycleStatus.FAILED;
        };
        return new LifecycleResult(status, effectiveId, result.body(), result.detail());
    }

    public enum LifecycleStatus {
        SPAWNED,
        LIVE,
        DORMANT,
        WOKEN,
        RECOVERED,
        HIBERNATED,
        WAITING,
        QUARANTINED,
        FAILED,
        SKIPPED
    }

    public record LifecycleResult(
            LifecycleStatus status,
            UUID citizenId,
            NumenPlayer body,
            String message) {

        public LifecycleResult {
            status = Objects.requireNonNull(status, "status");
            message = Objects.requireNonNull(message, "message");
        }

        static LifecycleResult failure(
                UUID citizenId, LifecycleStatus status, String message) {
            return new LifecycleResult(status, citizenId, null, message);
        }

        public boolean successful() {
            return status == LifecycleStatus.SPAWNED
                    || status == LifecycleStatus.LIVE
                    || status == LifecycleStatus.WOKEN
                    || status == LifecycleStatus.RECOVERED
                    || status == LifecycleStatus.HIBERNATED;
        }
    }

    private record HomeResolution(
            ServerLevel level,
            Vec3 position,
            String detail,
            boolean permanentFailure) {

        static HomeResolution success(ServerLevel level, Vec3 position) {
            return new HomeResolution(level, position, "home resolved", false);
        }

        static HomeResolution permanentFailure(String detail) {
            return new HomeResolution(null, null, detail, true);
        }

        static HomeResolution retryableFailure(String detail) {
            return new HomeResolution(null, null, detail, false);
        }

        boolean successful() {
            return level != null && position != null;
        }
    }
}
