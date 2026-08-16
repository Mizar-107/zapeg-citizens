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

    public record RegisteredCompanion(java.util.UUID citizenId, String name, long diedAt) {}
}
