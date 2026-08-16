package io.github.mizar107.zapegcitizens.compat;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

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

    public static NumenPlayer spawnFor(ServerPlayer owner, String name) {
        ServerLevel level = (ServerLevel) owner.level();
        MinecraftServer server = level.getServer();
        NumenPlayer citizen = Companions.summon(
                server, owner.getUUID(), name, level, owner.position());
        Companions.syncRosterToOwner(server, owner);
        return citizen;
    }

    public static NumenPlayer findLiveOwned(ServerPlayer owner, java.util.UUID citizenId) {
        NumenPlayer citizen = NumenPlayer.findByUuid(owner.server, citizenId);
        return citizen != null && citizen.isOwnedByPlayer(owner.getUUID()) ? citizen : null;
    }

    public static NumenPlayer findLive(MinecraftServer server, java.util.UUID citizenId) {
        return NumenPlayer.findByUuid(server, citizenId);
    }

    public static void makeDormant(MinecraftServer server, NumenPlayer citizen) {
        Companions.dormant(server, citizen);
    }

    /**
     * Permanently removes the exact managed body identity. A live body's inventory
     * is dropped, matching Numen's normal confirmed-dismiss behavior.
     */
    public static DismissResult dismissManaged(
            MinecraftServer server, java.util.UUID citizenId, java.util.UUID bodyOwnerId) {
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
            MinecraftServer server, java.util.UUID citizenId, java.util.UUID bodyOwnerId) {
        NumenPlayer live = NumenPlayer.findByUuid(server, citizenId);
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(citizenId);
        if (live == null && entry == null) {
            return OwnershipCheck.MISSING;
        }
        if ((live != null && !live.isOwnedByPlayer(bodyOwnerId))
                || (entry != null && !entry.owner().equals(bodyOwnerId))) {
            return OwnershipCheck.MISMATCH;
        }
        return OwnershipCheck.MATCH;
    }

    public record RegisteredCompanion(java.util.UUID citizenId, String name, long diedAt) {}

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
