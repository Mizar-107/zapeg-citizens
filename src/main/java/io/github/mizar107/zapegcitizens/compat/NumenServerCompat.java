package io.github.mizar107.zapegcitizens.compat;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.CompanionChunkLoader;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The deliberately small boundary around Numen's not-yet-public server lifecycle API.
 * Keep the exact Numen/API versions pinned until an upstream server gateway replaces this.
 */
public final class NumenServerCompat {

    private NumenServerCompat() {}

    /**
     * Resolve an existing owner-scoped name case-insensitively before calling Numen.
     * Numen's internal lookup is case-sensitive, so skipping this step could create
     * a second UUID for "Atlas" and "atlas".
     */
    public static Optional<RegisteredCompanion> findRegisteredOwned(
            ServerPlayer owner, String requestedName) {
        return CompanionRegistry.get(owner.server)
                .ownedBy(owner.getUUID())
                .stream()
                .filter(entry -> entry.getValue().name().equalsIgnoreCase(requestedName))
                .map(entry -> new RegisteredCompanion(
                        entry.getKey(), entry.getValue().name(), entry.getValue().diedAt()))
                .findFirst();
    }

    /**
     * Checks every persisted Numen row, including dormant bodies owned by someone else.
     * Numen exposes only an owner-scoped iterator, so this pinned compatibility boundary
     * reads its public SavedData serialization instead of reflecting into private state.
     */
    public static boolean isRegisteredNameInUse(
            MinecraftServer server, String requestedName) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(requestedName, "requestedName");
        CompoundTag snapshot = CompanionRegistry.get(server).save(new CompoundTag());
        if (!snapshot.contains("companions", Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag companions = snapshot.getCompound("companions");
        for (String citizenKey : companions.getAllKeys()) {
            if (!companions.contains(citizenKey, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag entry = companions.getCompound(citizenKey);
            if (entry.contains("name", Tag.TAG_STRING)
                    && entry.getString("name").equalsIgnoreCase(requestedName)) {
                return true;
            }
        }
        return false;
    }

    public static NumenPlayer spawnFor(ServerPlayer owner, String name) {
        ServerLevel level = (ServerLevel) owner.level();
        MinecraftServer server = level.getServer();
        NumenPlayer citizen = Companions.summon(
                server, owner.getUUID(), name, level, owner.position());
        Companions.syncRosterToOwner(server, owner);
        return citizen;
    }

    /**
     * Creates a new server-owned body under a non-player technical owner.
     *
     * <p>The caller must reserve the returned body UUID in ZapeG's registry. This method refuses
     * to reuse an existing Numen row or an online identity, then validates both Numen's live body
     * and persistent row after creation. A partially created body is made dormant on invariant
     * failure, but a mismatched persistent row is never deleted automatically.
     */
    public static ServerBodyResult spawnServerBody(
            MinecraftServer server,
            UUID technicalOwnerId,
            String name,
            ServerLevel level,
            Vec3 position) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(technicalOwnerId, "technicalOwnerId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");

        ServerBodyResult principalConflict = technicalOwnerConflict(server, technicalOwnerId);
        if (principalConflict != null) {
            return principalConflict;
        }
        if (server.getPlayerList().getPlayerByName(name) != null) {
            return ServerBodyResult.failure(
                    ServerBodyStatus.NAME_IN_USE,
                    "the requested body name is already online");
        }
        if (isRegisteredNameInUse(server, name)) {
            return ServerBodyResult.failure(
                    ServerBodyStatus.NAME_ALREADY_REGISTERED,
                    "Numen already has a registered body with that name");
        }

        NumenPlayer body = null;
        try {
            body = Companions.summon(server, technicalOwnerId, name, level, position);
            ServerBodyResult postcondition = validateCreatedBody(
                    server, body, technicalOwnerId, ServerBodyStatus.SPAWNED);
            if (postcondition.successful()) {
                return postcondition;
            }
            makePartiallyCreatedBodyDormant(server, body);
            return postcondition;
        } catch (RuntimeException exception) {
            makePartiallyCreatedBodyDormant(server, body);
            return ServerBodyResult.failure(
                    ServerBodyStatus.FAILED,
                    exceptionMessage("Numen could not create the server body", exception));
        }
    }

    public static NumenPlayer findLiveOwned(ServerPlayer owner, java.util.UUID citizenId) {
        NumenPlayer citizen = NumenPlayer.findByUuid(owner.server, citizenId);
        return citizen != null && citizen.isOwnedByPlayer(owner.getUUID()) ? citizen : null;
    }

    public static NumenPlayer findLive(MinecraftServer server, java.util.UUID citizenId) {
        return NumenPlayer.findByUuid(server, citizenId);
    }

    /** Returns a live body only when both its live and persistent owners match exactly. */
    public static NumenPlayer findLiveManaged(
            MinecraftServer server, UUID citizenId, UUID bodyOwnerId) {
        if (checkManagedOwnership(server, citizenId, bodyOwnerId) != OwnershipCheck.MATCH) {
            return null;
        }
        NumenPlayer body = NumenPlayer.findByUuid(server, citizenId);
        return body != null && body.isOwnedByPlayer(bodyOwnerId) ? body : null;
    }

    /** Read-only state used by the always-awake server lifecycle reconciler. */
    public static ServerBodyResult inspectServerBody(
            MinecraftServer server, UUID citizenId, UUID technicalOwnerId) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(citizenId, "citizenId");
        Objects.requireNonNull(technicalOwnerId, "technicalOwnerId");

        ServerBodyResult principalConflict = technicalOwnerConflict(server, technicalOwnerId);
        if (principalConflict != null) {
            return principalConflict;
        }

        ServerPlayer occupant = server.getPlayerList().getPlayer(citizenId);
        if (occupant != null && !(occupant instanceof NumenPlayer)) {
            return ServerBodyResult.failure(
                    ServerBodyStatus.BODY_ID_IN_USE,
                    "the managed body UUID is occupied by a real player");
        }
        NumenPlayer live = occupant instanceof NumenPlayer citizen ? citizen : null;
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(citizenId);
        if (entry == null) {
            return new ServerBodyResult(
                    ServerBodyStatus.MISSING, live, 0L,
                    "Numen's persistent companion row is missing");
        }
        if (!entry.owner().equals(technicalOwnerId)
                || (live != null && !live.isOwnedByPlayer(technicalOwnerId))) {
            return new ServerBodyResult(
                    ServerBodyStatus.OWNER_MISMATCH, live, entry.diedAt(),
                    "Numen's body-owner identity does not match the managed registry");
        }
        if (entry.diedAt() > 0L) {
            return new ServerBodyResult(
                    ServerBodyStatus.DEAD, live, entry.diedAt(),
                    "the body is waiting for server-owned death recovery");
        }
        return new ServerBodyResult(
                live == null ? ServerBodyStatus.DORMANT : ServerBodyStatus.LIVE,
                live,
                0L,
                live == null ? "the body is dormant" : "the body is live");
    }

    /** Wakes an alive-but-dormant body with the same UUID and persisted player data. */
    public static ServerBodyResult wakeServerBody(
            MinecraftServer server, UUID citizenId, UUID technicalOwnerId) {
        ServerBodyResult before = inspectServerBody(server, citizenId, technicalOwnerId);
        if (before.status() == ServerBodyStatus.LIVE
                || before.status() == ServerBodyStatus.DEAD
                || before.quarantinable()) {
            return before;
        }
        if (before.status() != ServerBodyStatus.DORMANT) {
            return before;
        }

        try {
            NumenPlayer body = Companions.respawn(server, citizenId);
            if (body == null) {
                return ServerBodyResult.failure(
                        ServerBodyStatus.FAILED,
                        "Numen returned no body while waking the registered identity");
            }
            return validateCreatedBody(
                    server, body, technicalOwnerId, ServerBodyStatus.WOKEN);
        } catch (RuntimeException exception) {
            return ServerBodyResult.failure(
                    ServerBodyStatus.FAILED,
                    exceptionMessage("Numen could not wake the server body", exception));
        }
    }

    /**
     * Recreates a dead body at its configured home and clears Numen's death marker only after
     * the live body and persistent technical-owner identity have both been validated.
     */
    public static ServerBodyResult recoverServerBody(
            MinecraftServer server,
            UUID citizenId,
            UUID technicalOwnerId,
            String name,
            ServerLevel homeLevel,
            Vec3 homePosition,
            float yaw,
            float pitch) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(homeLevel, "homeLevel");
        Objects.requireNonNull(homePosition, "homePosition");

        ServerBodyResult before = inspectServerBody(server, citizenId, technicalOwnerId);
        if (before.status() != ServerBodyStatus.DEAD) {
            return before;
        }
        if (before.body() != null) {
            return new ServerBodyResult(
                    ServerBodyStatus.DEAD,
                    before.body(),
                    before.diedAt(),
                    "Numen has marked the body dead but has not removed it yet");
        }

        NumenPlayer body = null;
        try {
            body = CompanionFactory.spawn(
                    server,
                    citizenId,
                    name,
                    technicalOwnerId,
                    homeLevel,
                    homePosition);
            ServerBodyResult postcondition = validateCreatedBody(
                    server, body, technicalOwnerId, ServerBodyStatus.RECOVERED);
            if (!postcondition.successful()) {
                makePartiallyCreatedBodyDormant(server, body);
                return postcondition;
            }

            body.moveTo(
                    homePosition.x,
                    homePosition.y,
                    homePosition.z,
                    yaw,
                    pitch);
            body.setHealth(body.getMaxHealth());
            body.clearFire();

            CompanionRegistry registry = CompanionRegistry.get(server);
            CompanionRegistry.Entry entry = registry.find(citizenId);
            if (entry == null) {
                makePartiallyCreatedBodyDormant(server, body);
                return ServerBodyResult.failure(
                        ServerBodyStatus.MISSING,
                        "Numen's persistent row disappeared during death recovery");
            }
            if (!entry.owner().equals(technicalOwnerId)
                    || !body.isOwnedByPlayer(technicalOwnerId)) {
                makePartiallyCreatedBodyDormant(server, body);
                return ServerBodyResult.failure(
                        ServerBodyStatus.OWNER_MISMATCH,
                        "Numen's technical owner changed during death recovery");
            }

            registry.put(
                    citizenId,
                    entry.movedTo(homeLevel.dimension(), body.blockPosition()));
            registry.markAlive(citizenId);
            CompanionChunkLoader.refresh(body);
            return new ServerBodyResult(
                    ServerBodyStatus.RECOVERED,
                    body,
                    0L,
                    "the dead body was recovered at home");
        } catch (RuntimeException exception) {
            makePartiallyCreatedBodyDormant(server, body);
            return ServerBodyResult.failure(
                    ServerBodyStatus.FAILED,
                    exceptionMessage("Numen could not recover the server body", exception));
        }
    }

    /** Refreshes Numen's radius-two, expiring chunk ticket for one validated live body. */
    public static ServerBodyResult refreshServerChunkTicket(
            MinecraftServer server, UUID citizenId, UUID technicalOwnerId) {
        ServerBodyResult state = inspectServerBody(server, citizenId, technicalOwnerId);
        if (state.status() != ServerBodyStatus.LIVE) {
            return state;
        }
        try {
            CompanionChunkLoader.refresh(state.body());
            return state;
        } catch (RuntimeException exception) {
            return ServerBodyResult.failure(
                    ServerBodyStatus.FAILED,
                    exceptionMessage("Numen could not refresh the body chunk ticket", exception));
        }
    }

    /** Stores the current dimension and block position without touching the death marker or skin. */
    public static ServerBodyResult snapshotServerPosition(
            MinecraftServer server, UUID citizenId, UUID technicalOwnerId) {
        ServerBodyResult state = inspectServerBody(server, citizenId, technicalOwnerId);
        if (state.status() != ServerBodyStatus.LIVE) {
            return state;
        }

        NumenPlayer body = state.body();
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = registry.find(citizenId);
        ServerLevel level = (ServerLevel) body.level();
        BlockPos position = body.blockPosition();
        if (entry.dimension().equals(level.dimension()) && entry.pos().equals(position)) {
            return new ServerBodyResult(
                    ServerBodyStatus.UNCHANGED,
                    body,
                    0L,
                    "the stored body position is already current");
        }
        registry.put(citizenId, entry.movedTo(level.dimension(), position));
        return new ServerBodyResult(
                ServerBodyStatus.SNAPSHOTTED,
                body,
                0L,
                "the current body position was persisted");
    }

    /** Snapshots and removes a live body while retaining its UUID, inventory, and Numen row. */
    public static ServerBodyResult hibernateServerBody(
            MinecraftServer server, UUID citizenId, UUID technicalOwnerId) {
        ServerBodyResult state = inspectServerBody(server, citizenId, technicalOwnerId);
        if (state.status() == ServerBodyStatus.DORMANT
                || state.status() == ServerBodyStatus.DEAD
                || state.quarantinable()) {
            return state;
        }
        if (state.status() != ServerBodyStatus.LIVE) {
            return state;
        }
        ServerBodyResult snapshot = snapshotServerPosition(
                server, citizenId, technicalOwnerId);
        if (snapshot.status() != ServerBodyStatus.SNAPSHOTTED
                && snapshot.status() != ServerBodyStatus.UNCHANGED) {
            return snapshot;
        }
        try {
            Companions.dormant(server, state.body());
            return new ServerBodyResult(
                    ServerBodyStatus.HIBERNATED,
                    null,
                    0L,
                    "the body was snapshotted and made dormant");
        } catch (RuntimeException exception) {
            return ServerBodyResult.failure(
                    ServerBodyStatus.FAILED,
                    exceptionMessage("Numen could not hibernate the server body", exception));
        }
    }

    public static void makeDormant(MinecraftServer server, NumenPlayer citizen) {
        Companions.dormant(server, citizen);
    }

    /**
     * Permanently removes the exact managed body identity. A live body's inventory
     * is dropped, matching Numen's normal confirmed-dismiss behavior.
     */
    public static DismissResult dismissManaged(
            MinecraftServer server, UUID citizenId, UUID bodyOwnerId) {
        OwnershipCheck ownership = checkManagedOwnership(server, citizenId, bodyOwnerId);
        if (ownership == OwnershipCheck.MISMATCH) {
            return DismissResult.OWNER_MISMATCH;
        }
        if (ownership == OwnershipCheck.MISSING) {
            return DismissResult.MISSING;
        }

        NumenPlayer live = NumenPlayer.findByUuid(server, citizenId);
        if (live != null) {
            live.getInventory().dropAll();
            Companions.dismiss(server, live);
        } else {
            CompanionRegistry registry = CompanionRegistry.get(server);
            registry.remove(citizenId);
        }

        ServerPlayer bodyOwner = server.getPlayerList().getPlayer(bodyOwnerId);
        if (bodyOwner != null && !(bodyOwner instanceof NumenPlayer)) {
            Companions.syncRosterToOwner(server, bodyOwner);
        }
        return DismissResult.REMOVED;
    }

    /** Read-only identity check used before any cancellation or destructive removal. */
    public static OwnershipCheck checkManagedOwnership(
            MinecraftServer server, UUID citizenId, UUID bodyOwnerId) {
        ServerPlayer occupant = server.getPlayerList().getPlayer(citizenId);
        if (occupant != null && !(occupant instanceof NumenPlayer)) {
            return OwnershipCheck.MISMATCH;
        }
        NumenPlayer live = occupant instanceof NumenPlayer citizen ? citizen : null;
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(citizenId);
        if (live == null && entry == null) {
            return OwnershipCheck.MISSING;
        }
        // A live body without Numen's durable row is a corrupt split-brain identity,
        // not a safely managed body. Entry-only is the normal dormant representation.
        if (live != null && entry == null) {
            return OwnershipCheck.MISMATCH;
        }
        if ((live != null && !live.isOwnedByPlayer(bodyOwnerId))
                || (entry != null && !entry.owner().equals(bodyOwnerId))) {
            return OwnershipCheck.MISMATCH;
        }
        return OwnershipCheck.MATCH;
    }

    private static ServerBodyResult validateCreatedBody(
            MinecraftServer server,
            NumenPlayer body,
            UUID technicalOwnerId,
            ServerBodyStatus successStatus) {
        if (body == null) {
            return ServerBodyResult.failure(
                    ServerBodyStatus.FAILED,
                    "Numen returned no live body");
        }
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(body.getUUID());
        if (entry == null) {
            return new ServerBodyResult(
                    ServerBodyStatus.MISSING,
                    body,
                    0L,
                    "Numen created a body without its persistent companion row");
        }
        if (!entry.owner().equals(technicalOwnerId)
                || !body.isOwnedByPlayer(technicalOwnerId)) {
            return new ServerBodyResult(
                    ServerBodyStatus.OWNER_MISMATCH,
                    body,
                    entry.diedAt(),
                    "Numen created a body with an unexpected technical owner");
        }
        return new ServerBodyResult(
                successStatus,
                body,
                entry.diedAt(),
                switch (successStatus) {
                    case SPAWNED -> "a new server-owned body was created";
                    case WOKEN -> "the dormant server-owned body was woken";
                    case RECOVERED -> "the dead server-owned body was recovered";
                    default -> "the server-owned body is ready";
                });
    }

    private static ServerBodyResult technicalOwnerConflict(
            MinecraftServer server, UUID technicalOwnerId) {
        ServerPlayer occupant = server.getPlayerList().getPlayer(technicalOwnerId);
        return occupant == null
                ? null
                : ServerBodyResult.failure(
                        ServerBodyStatus.TECHNICAL_OWNER_ONLINE,
                        "the non-player technical owner UUID is occupied online");
    }

    private static void makePartiallyCreatedBodyDormant(
            MinecraftServer server, NumenPlayer body) {
        if (body == null || body.isRemoved()) {
            return;
        }
        try {
            CompanionFactory.despawn(server, body);
        } catch (RuntimeException ignored) {
            // The original invariant failure is more useful to the caller. Never delete a
            // potentially mismatched persistent row while attempting best-effort cleanup.
        }
    }

    private static String exceptionMessage(String prefix, RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? prefix + " (" + exception.getClass().getSimpleName() + ")"
                : prefix + ": " + message.replace('\n', ' ').replace('\r', ' ').strip();
    }

    public record RegisteredCompanion(UUID citizenId, String name, long diedAt) {}

    /** Result of a pinned-Numen server-body operation. */
    public record ServerBodyResult(
            ServerBodyStatus status,
            NumenPlayer body,
            long diedAt,
            String detail) {

        public ServerBodyResult {
            status = Objects.requireNonNull(status, "status");
            detail = Objects.requireNonNull(detail, "detail");
        }

        public static ServerBodyResult failure(ServerBodyStatus status, String detail) {
            return new ServerBodyResult(status, null, 0L, detail);
        }

        public boolean successful() {
            return status == ServerBodyStatus.SPAWNED
                    || status == ServerBodyStatus.LIVE
                    || status == ServerBodyStatus.WOKEN
                    || status == ServerBodyStatus.RECOVERED
                    || status == ServerBodyStatus.SNAPSHOTTED
                    || status == ServerBodyStatus.UNCHANGED
                    || status == ServerBodyStatus.HIBERNATED;
        }

        public boolean quarantinable() {
            return status == ServerBodyStatus.MISSING
                    || status == ServerBodyStatus.OWNER_MISMATCH
                    || status == ServerBodyStatus.TECHNICAL_OWNER_ONLINE
                    || status == ServerBodyStatus.BODY_ID_IN_USE;
        }
    }

    public enum ServerBodyStatus {
        SPAWNED,
        LIVE,
        DORMANT,
        DEAD,
        WOKEN,
        RECOVERED,
        SNAPSHOTTED,
        UNCHANGED,
        HIBERNATED,
        MISSING,
        OWNER_MISMATCH,
        TECHNICAL_OWNER_ONLINE,
        BODY_ID_IN_USE,
        NAME_IN_USE,
        NAME_ALREADY_REGISTERED,
        FAILED
    }

    public enum DismissResult {
        REMOVED,
        MISSING,
        OWNER_MISMATCH
    }

    public enum OwnershipCheck {
        MATCH,
        MISSING,
        MISMATCH
    }
}
