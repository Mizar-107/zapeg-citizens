package io.github.mizar107.zapegcitizens.command;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.mizar107.zapegcitizens.brain.CitizenBrainCoordinator;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CitizenCommands {

    private CitizenCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("citizen")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> spawn(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                EntityArgument.getPlayer(context, "player"))))))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> remove(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("brain-status")
                        .executes(context -> brainStatus(context.getSource()))));
    }

    private static int spawn(CommandSourceStack source, String requestedName, ServerPlayer owner)
            throws CommandSyntaxException {
        if (owner instanceof NumenPlayer) {
            source.sendFailure(Component.literal(
                    "A citizen must be assigned to a real online player, not another citizen."));
            return 0;
        }

        String validatedName = requestedName.strip();
        if (!validatedName.matches("[A-Za-z0-9_]{3,16}")) {
            source.sendFailure(Component.literal(
                    "Citizen names must be 3-16 letters, digits, or underscores."));
            return 0;
        }

        CitizenRegistryData registry = CitizenRegistryData.get(source.getServer());
        Optional<CitizenRegistryData.CitizenRecord> reservation =
                registry.findByName(validatedName);
        if (reservation.isPresent()) {
            CitizenRegistryData.CitizenRecord record = reservation.orElseThrow();
            if (!record.logicalOwner().matchesPlayer(owner.getUUID())) {
                source.sendFailure(Component.literal(
                        "The citizen name '" + record.name() + "' is already reserved."));
                return 0;
            }

            NumenPlayer live = NumenServerCompat.findLiveOwned(owner, record.citizenId());
            if (live == null) {
                source.sendFailure(Component.literal(
                        "Citizen '" + record.name() + "' is registered but not live. "
                                + "Wait for Numen's respawn or reconnect the owner; do not spawn it again."));
                return 0;
            }

            source.sendSuccess(() -> Component.literal(
                    "Citizen '" + record.name() + "' is already active for "
                            + owner.getGameProfile().getName() + " (" + live.getUUID() + ")."), true);
            return 1;
        }

        Optional<NumenServerCompat.RegisteredCompanion> unmanaged =
                NumenServerCompat.findRegisteredOwned(owner, validatedName);
        if (unmanaged.isPresent()) {
            source.sendFailure(Component.literal(
                    "A Numen companion named '" + unmanaged.orElseThrow().name()
                            + "' already exists outside the managed citizen registry. "
                            + "Use another name until an adoption command is available."));
            return 0;
        }

        String name = validatedName;

        ServerPlayer occupant = source.getServer().getPlayerList().getPlayerByName(name);
        if (occupant != null) {
            source.sendFailure(Component.literal(
                    "The name '" + name + "' is already used by an online player or citizen."));
            return 0;
        }

        NumenPlayer citizen = NumenServerCompat.spawnFor(owner, name);
        registry.reservePlayer(name, citizen.getUUID(), owner.getUUID());

        source.sendSuccess(() -> Component.literal(
                "Spawned citizen '" + name + "' for " + owner.getGameProfile().getName()
                        + " (" + citizen.getUUID() + ")."), true);
        owner.sendSystemMessage(Component.literal(
                "[Citizens] " + name + " is yours. Give work with @" + name
                        + " <task>; use @" + name + " stop to cancel."));
        return 1;
    }

    private static int list(CommandSourceStack source) {
        List<String> rows = new ArrayList<>();
        for (CitizenRegistryData.CitizenRecord record
                : CitizenRegistryData.get(source.getServer()).all()) {
            java.util.Optional<java.util.UUID> ownerId = record.logicalOwner().playerId();
            ServerPlayer owner = ownerId
                    .map(id -> source.getServer().getPlayerList().getPlayer(id))
                    .orElse(null);
            String ownerName = owner == null
                    ? record.logicalOwner().kind().name().toLowerCase(java.util.Locale.ROOT)
                            + ":" + record.logicalOwner().id()
                    : owner.getGameProfile().getName();
            boolean live = NumenPlayer.findByUuid(source.getServer(), record.citizenId()) != null;
            rows.add(record.name() + " -> " + ownerName + " [" + (live ? "live" : "dormant")
                    + "] (" + record.citizenId() + ")");
        }
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No managed citizens."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Managed citizens: " + String.join(", ", rows)), false);
        return rows.size();
    }

    private static int remove(CommandSourceStack source, String requestedName) {
        CitizenRegistryData registry = CitizenRegistryData.get(source.getServer());
        CitizenRegistryData.CitizenRecord record = registry.findByName(requestedName)
                .orElse(null);
        if (record == null) {
            source.sendFailure(Component.literal(
                    "No managed citizen named '" + requestedName + "'."));
            return 0;
        }

        NumenServerCompat.OwnershipCheck ownership = NumenServerCompat.checkManagedOwnership(
                source.getServer(), record.citizenId(), record.bodyOwnerId());
        if (ownership == NumenServerCompat.OwnershipCheck.MISMATCH) {
            CitizenBrainCoordinator.instance().stopForOwnershipMismatch(record.citizenId());
            source.sendFailure(Component.literal(
                    "Refused to remove '" + record.name()
                            + "': its Numen body-owner identity does not match the managed registry."));
            return 0;
        }

        CitizenBrainCoordinator.instance().stopForRemoval(source.getServer(), record.citizenId());
        NumenServerCompat.DismissResult result = NumenServerCompat.dismissManaged(
                source.getServer(), record.citizenId(), record.bodyOwnerId());
        if (result == NumenServerCompat.DismissResult.OWNER_MISMATCH) {
            source.sendFailure(Component.literal(
                    "Refused to remove '" + record.name()
                            + "': its Numen body-owner identity does not match the managed registry."));
            return 0;
        }
        if (!registry.remove(record)) {
            source.sendFailure(Component.literal(
                    "Citizen body removal finished, but its managed reservation changed; "
                            + "inspect the server log before retrying."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Removed citizen '" + record.name() + "' ("
                        + (result == NumenServerCompat.DismissResult.REMOVED
                                ? "body/registry removed"
                                : "stale reservation cleared")
                        + ")."), true);
        return 1;
    }

    private static int brainStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                CitizenBrainCoordinator.instance().statusSummary()), false);
        return CitizenBrainCoordinator.instance().isEnabled() ? 1 : 0;
    }
}
