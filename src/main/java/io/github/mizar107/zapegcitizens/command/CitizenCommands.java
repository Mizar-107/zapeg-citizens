package io.github.mizar107.zapegcitizens.command;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.mizar107.zapegcitizens.brain.CitizenBrainCoordinator;
import io.github.mizar107.zapegcitizens.brain.CitizenJobManager;
import io.github.mizar107.zapegcitizens.brain.CitizenJobManager.JobOperation;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.CitizenProfile;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.CitizenRecord;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.HomeAnchor;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData.OwnerKind;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobRecord;
import io.github.mizar107.zapegcitizens.lifecycle.ServerCitizenLifecycleManager;
import io.github.mizar107.zapegcitizens.lifecycle.ServerCitizenLifecycleManager.LifecycleResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CitizenCommands {

    static final String DEFAULT_SERVER_ROLE = "lore";
    static final String DEFAULT_SERVER_FACTION = CitizenRegistryData.DEFAULT_SERVER_FACTION;
    static final String DEFAULT_LOGICAL_SERVER_ID = "world";

    private static final Pattern CITIZEN_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern PROFILE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9_.:-]{1,32}");

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
                .then(Commands.literal("spawn-server")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> spawnServer(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        DEFAULT_SERVER_ROLE,
                                        DEFAULT_SERVER_FACTION,
                                        ""))
                                .then(Commands.argument("role", StringArgumentType.string())
                                        .executes(context -> spawnServer(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "role"),
                                                DEFAULT_SERVER_FACTION,
                                                ""))
                                        .then(Commands.argument(
                                                        "faction", StringArgumentType.string())
                                                .executes(context -> spawnServer(
                                                        context.getSource(),
                                                        StringArgumentType.getString(
                                                                context, "name"),
                                                        StringArgumentType.getString(
                                                                context, "role"),
                                                        StringArgumentType.getString(
                                                                context, "faction"),
                                                        ""))
                                                .then(Commands.argument(
                                                                "persona",
                                                                StringArgumentType.greedyString())
                                                        .executes(context -> spawnServer(
                                                                context.getSource(),
                                                                StringArgumentType.getString(
                                                                        context, "name"),
                                                                StringArgumentType.getString(
                                                                        context, "role"),
                                                                StringArgumentType.getString(
                                                                        context, "faction"),
                                                                StringArgumentType.getString(
                                                                        context, "persona"))))))))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> remove(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("task")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument(
                                                "prompt", StringArgumentType.greedyString())
                                        .executes(context -> task(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(
                                                        context, "prompt"))))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> stop(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("status")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> jobStatus(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("jobs")
                        .executes(context -> jobs(context.getSource(), null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> jobs(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("resume")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> resume(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        ""))
                                .then(Commands.argument(
                                                "answer", StringArgumentType.greedyString())
                                        .executes(context -> resume(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(
                                                        context, "answer"))))))
                .then(Commands.literal("wake")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> wake(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("persona")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument(
                                                "text", StringArgumentType.greedyString())
                                        .executes(context -> persona(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(
                                                        context, "text"))))))
                .then(Commands.literal("set-home")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> setHome(
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
        if (!validCitizenName(validatedName)) {
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

            NumenPlayer live = NumenServerCompat.findLiveManaged(
                    source.getServer(), record.citizenId(), record.bodyOwnerId());
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

        if (NumenServerCompat.isRegisteredNameInUse(source.getServer(), validatedName)) {
            source.sendFailure(Component.literal(
                    "A Numen companion named '" + validatedName
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

    private static int spawnServer(
            CommandSourceStack source,
            String requestedName,
            String requestedRole,
            String requestedFaction,
            String requestedPersona) {
        if (!hasExplicitWorldPosition(source)) {
            source.sendFailure(Component.literal(
                    "Console/RCON spawning needs an explicit position. Use /execute in "
                            + "<dimension> positioned <x> <y> <z> run citizen spawn-server ..."));
            return 0;
        }
        String name = requestedName.strip();
        if (!validCitizenName(name)) {
            source.sendFailure(Component.literal(
                    "Citizen names must be 3-16 letters, digits, or underscores."));
            return 0;
        }

        String role = requestedRole.strip();
        String faction = requestedFaction.strip();
        if (!validProfileIdentifier(role) || !validProfileIdentifier(faction)) {
            source.sendFailure(Component.literal(
                    "Citizen roles and factions must be 1-32 letters, digits, dots, "
                            + "underscores, colons, or hyphens."));
            return 0;
        }

        String persona = requestedPersona.strip();
        if (persona.length() > CitizenRegistryData.MAX_PERSONA_LENGTH) {
            source.sendFailure(Component.literal(
                    "Citizen personas must contain at most "
                            + CitizenRegistryData.MAX_PERSONA_LENGTH + " characters."));
            return 0;
        }

        CitizenRegistryData registry = CitizenRegistryData.get(source.getServer());
        CitizenRecord existing = registry.findByName(name).orElse(null);
        if (existing != null) {
            if (existing.logicalOwner().kind() != OwnerKind.SERVER) {
                source.sendFailure(Component.literal(
                        "The citizen name '" + existing.name() + "' is already reserved."));
                return 0;
            }
            LifecycleResult state = ServerCitizenLifecycleManager.instance()
                    .ensureAwake(source.getServer(), existing);
            if (!state.successful()) {
                source.sendFailure(Component.literal(
                        "Server citizen '" + existing.name() + "' is already registered, but "
                                + state.message() + "."));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "Server citizen '" + existing.name() + "' is already active ("
                            + existing.citizenId() + ")."), true);
            return 1;
        }

        ServerLevel level = source.getLevel();
        Vec3 position = source.getPosition();
        Vec2 rotation = source.getRotation();
        HomeAnchor home = new HomeAnchor(
                level.dimension().location().toString(),
                position.x,
                position.y,
                position.z,
                rotation.y,
                rotation.x);
        UUID technicalOwnerId = registry.serverPrincipalId();
        LifecycleResult spawned = ServerCitizenLifecycleManager.instance().spawn(
                source.getServer(), level, position, home, name, technicalOwnerId);
        if (!spawned.successful() || spawned.body() == null) {
            source.sendFailure(Component.literal(
                    "Could not spawn server citizen '" + name + "': "
                            + spawned.message() + "."));
            return 0;
        }

        NumenPlayer body = spawned.body();
        try {
            registry.reserveServer(
                    name,
                    body.getUUID(),
                    DEFAULT_LOGICAL_SERVER_ID,
                    technicalOwnerId,
                    new CitizenProfile(role, faction, persona),
                    home);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            NumenServerCompat.DismissResult rollback = NumenServerCompat.dismissManaged(
                    source.getServer(), body.getUUID(), technicalOwnerId);
            source.sendFailure(Component.literal(
                    "Could not reserve server citizen '" + name + "': "
                            + cleanMessage(exception)
                            + ". The newly created body rollback was "
                            + rollback.name().toLowerCase(Locale.ROOT) + "."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Spawned server citizen '" + name + "' as " + role + " for faction "
                        + faction + " (" + body.getUUID() + "). Players can talk with @"
                        + name + " <message>; operators can assign physical work with "
                        + "/citizen task " + name + " <task>."), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        List<String> rows = new ArrayList<>();
        for (CitizenRecord record : CitizenRegistryData.get(source.getServer()).all()) {
            Optional<UUID> ownerId = record.logicalOwner().playerId();
            ServerPlayer owner = ownerId
                    .map(id -> source.getServer().getPlayerList().getPlayer(id))
                    .orElse(null);
            String ownerName = owner == null
                    ? record.logicalOwner().kind().name().toLowerCase(Locale.ROOT)
                            + ":" + record.logicalOwner().id()
                    : owner.getGameProfile().getName();
            String state = citizenState(source, record);
            String home = record.home().map(CitizenCommands::formatHome).orElse("unset");
            String persona = record.persona().isEmpty()
                    ? "unset"
                    : record.persona().length() + " chars";
            rows.add(record.name() + " -> " + ownerName + " [" + state + "] role="
                    + record.role() + " faction=" + record.faction() + " home=" + home
                    + " persona=" + persona + " (" + record.citizenId() + ")");
        }
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No managed citizens."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Managed citizens:\n - " + String.join("\n - ", rows)), false);
        return rows.size();
    }

    private static int task(CommandSourceStack source, String requestedName, String prompt)
            throws CommandSyntaxException {
        ServerPlayer actor = requireRealPlayer(source,
                "Server citizen tasks must be submitted by an in-game operator.");
        if (actor == null) {
            return 0;
        }
        CitizenRecord record = requireServerCitizen(source, requestedName);
        if (record == null) {
            return 0;
        }

        LifecycleResult state = ServerCitizenLifecycleManager.instance()
                .ensureAwake(source.getServer(), record);
        NumenPlayer body = state.successful()
                ? NumenServerCompat.findLiveManaged(
                        source.getServer(), record.citizenId(), record.bodyOwnerId())
                : null;
        if (body == null) {
            source.sendFailure(Component.literal(
                    "Server citizen '" + record.name() + "' is unavailable: "
                            + state.message() + "."));
            return 0;
        }

        JobOperation operation = CitizenJobManager.instance().submit(
                actor, record, body, prompt);
        if (!operation.successful()) {
            source.sendFailure(Component.literal(operation.message()));
            return 0;
        }
        return 1;
    }

    private static int stop(CommandSourceStack source, String requestedName) {
        CitizenRecord record = requireCitizen(source, requestedName);
        if (record == null) {
            return 0;
        }

        NumenServerCompat.OwnershipCheck ownership = NumenServerCompat.checkManagedOwnership(
                source.getServer(), record.citizenId(), record.bodyOwnerId());
        if (ownership == NumenServerCompat.OwnershipCheck.MISMATCH) {
            CitizenJobManager.instance().ownershipMismatch(
                    source.getServer(), record.citizenId(),
                    "managed body-owner identity is inconsistent");
            CitizenBrainCoordinator.instance().stopForOwnershipMismatch(record.citizenId());
            source.sendFailure(Component.literal(
                    "Refused to stop '" + record.name()
                            + "': its Numen body-owner identity is inconsistent."));
            return 0;
        }

        // Operator stop matches player "@Name stop": the active job AND the
        // waiting queue are cleared, so the citizen genuinely stands down.
        JobOperation operation = CitizenJobManager.instance().stopAll(
                source.getServer(), record, "canceled by an operator");
        boolean dialogueStopped = CitizenBrainCoordinator.instance()
                .stopForRemoval(source.getServer(), record);
        if (!operation.successful() && !dialogueStopped) {
            source.sendFailure(Component.literal(operation.message()));
            return 0;
        }
        String message = operation.successful()
                ? operation.message()
                : "Stopped the active dialogue for " + record.name() + ".";
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int jobStatus(CommandSourceStack source, String requestedName) {
        CitizenRecord record = requireCitizen(source, requestedName);
        if (record == null) {
            return 0;
        }
        JobRecord job = CitizenJobManager.instance()
                .status(source.getServer(), record.citizenId())
                .orElse(null);
        if (job == null) {
            source.sendSuccess(() -> Component.literal(
                    "Citizen '" + record.name() + "' has no active job."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                record.name() + ": " + CitizenJobManager.instance().formatStatus(job)), false);
        return 1;
    }

    private static int jobs(CommandSourceStack source, String requestedName) {
        List<JobRecord> jobs;
        String heading;
        if (requestedName == null) {
            jobs = CitizenRegistryData.get(source.getServer()).all().stream()
                    .flatMap(record -> CitizenJobManager.instance()
                            .history(source.getServer(), record.citizenId()).stream())
                    .toList();
            heading = "Citizen jobs";
        } else {
            CitizenRecord record = requireCitizen(source, requestedName);
            if (record == null) {
                return 0;
            }
            jobs = CitizenJobManager.instance().history(source.getServer(), record.citizenId());
            heading = record.name() + " jobs";
        }
        if (jobs.isEmpty()) {
            source.sendSuccess(() -> Component.literal(heading + ": none."), false);
            return 0;
        }
        List<String> rows = jobs.stream()
                .sorted(Comparator.comparingLong(JobRecord::updatedGameTime).reversed())
                .limit(10)
                .map(CitizenJobManager.instance()::formatStatus)
                .toList();
        source.sendSuccess(() -> Component.literal(
                heading + ":\n - " + String.join("\n - ", rows)), false);
        return rows.size();
    }

    private static int resume(
            CommandSourceStack source, String requestedName, String answer) {
        CitizenRecord record = requireCitizen(source, requestedName);
        if (record == null) {
            return 0;
        }
        JobOperation operation = CitizenJobManager.instance().resume(
                source.getServer(), record, answer);
        if (!operation.successful()) {
            source.sendFailure(Component.literal(operation.message()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(operation.message()), true);
        return 1;
    }

    private static int wake(CommandSourceStack source, String requestedName) {
        CitizenRecord record = requireServerCitizen(source, requestedName);
        if (record == null) {
            return 0;
        }

        ServerCitizenLifecycleManager lifecycle = ServerCitizenLifecycleManager.instance();
        // Wake is the explicit operator retry path after repairing a home or Numen row. Clearing
        // quarantine is safe because ensureAwake immediately rechecks every ownership invariant.
        lifecycle.clearQuarantine(record.citizenId());
        LifecycleResult result = lifecycle.ensureAwake(source.getServer(), record);
        if (!result.successful()) {
            source.sendFailure(Component.literal(
                    "Could not wake server citizen '" + record.name() + "': "
                            + result.message() + "."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Server citizen '" + record.name() + "' is "
                        + result.status().name().toLowerCase(Locale.ROOT) + "."), true);
        return 1;
    }

    private static int persona(
            CommandSourceStack source, String requestedName, String requestedPersona) {
        CitizenRecord record = requireServerCitizen(source, requestedName);
        if (record == null) {
            return 0;
        }
        String persona = requestedPersona.strip();
        if (persona.isEmpty() || persona.length() > CitizenRegistryData.MAX_PERSONA_LENGTH) {
            source.sendFailure(Component.literal(
                    "Citizen personas must contain 1-"
                            + CitizenRegistryData.MAX_PERSONA_LENGTH + " characters."));
            return 0;
        }

        try {
            CitizenRegistryData.get(source.getServer()).updatePersona(record.name(), persona)
                    .orElseThrow();
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(
                    "Could not update '" + record.name() + "': "
                            + cleanMessage(exception) + "."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Updated the persona for server citizen '" + record.name()
                        + "' (" + persona.length() + " characters)."), true);
        return 1;
    }

    private static int setHome(CommandSourceStack source, String requestedName) {
        CitizenRecord record = requireServerCitizen(source, requestedName);
        if (record == null) {
            return 0;
        }
        if (!hasExplicitWorldPosition(source)) {
            source.sendFailure(Component.literal(
                    "Console/RCON home changes need an explicit position. Use /execute in "
                            + "<dimension> positioned <x> <y> <z> run citizen set-home ..."));
            return 0;
        }
        Vec3 position = source.getPosition();
        Vec2 rotation = source.getRotation();
        HomeAnchor home = new HomeAnchor(
                source.getLevel().dimension().location().toString(),
                position.x,
                position.y,
                position.z,
                rotation.y,
                rotation.x);
        CitizenRegistryData.get(source.getServer()).updateHome(record.name(), home)
                .orElseThrow();
        source.sendSuccess(() -> Component.literal(
                "Set the recovery home for server citizen '" + record.name() + "' to "
                        + formatHome(home) + "."), true);
        return 1;
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
            CitizenJobManager.instance().ownershipMismatch(
                    source.getServer(), record.citizenId(),
                    "managed body-owner identity is inconsistent");
            CitizenBrainCoordinator.instance().stopForOwnershipMismatch(record.citizenId());
            source.sendFailure(Component.literal(
                    "Refused to remove '" + record.name()
                            + "': its Numen body-owner identity does not match the managed registry."));
            return 0;
        }

        CitizenJobManager.instance().cancel(
                source.getServer(), record, "citizen removed by an operator");
        CitizenBrainCoordinator.instance().stopForRemoval(source.getServer(), record);
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
        if (record.logicalOwner().kind() == OwnerKind.SERVER) {
            ServerCitizenLifecycleManager.instance().clearQuarantine(record.citizenId());
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
                CitizenBrainCoordinator.instance().statusSummary()
                        + " Durable jobs: "
                        + (CitizenJobManager.instance().isEnabled() ? "enabled" : "disabled")
                        + ", " + CitizenJobManager.instance().inFlightCount()
                        + " HTTP operation(s) in flight."), false);
        return CitizenBrainCoordinator.instance().isEnabled()
                && CitizenJobManager.instance().isEnabled() ? 1 : 0;
    }

    private static CitizenRecord requireCitizen(
            CommandSourceStack source, String requestedName) {
        CitizenRecord record = CitizenRegistryData.get(source.getServer())
                .findByName(requestedName.strip())
                .orElse(null);
        if (record == null) {
            source.sendFailure(Component.literal(
                    "No managed citizen named '" + requestedName + "'."));
        }
        return record;
    }

    private static CitizenRecord requireServerCitizen(
            CommandSourceStack source, String requestedName) {
        CitizenRecord record = CitizenRegistryData.get(source.getServer())
                .findByName(requestedName.strip())
                .orElse(null);
        if (record == null) {
            source.sendFailure(Component.literal(
                    "No managed citizen named '" + requestedName + "'."));
            return null;
        }
        if (record.logicalOwner().kind() != OwnerKind.SERVER) {
            source.sendFailure(Component.literal(
                    "Citizen '" + record.name() + "' is player-owned; this command only "
                            + "controls server-owned citizens."));
            return null;
        }
        return record;
    }

    private static ServerPlayer requireRealPlayer(
            CommandSourceStack source, String failureMessage) throws CommandSyntaxException {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.literal(failureMessage));
            return null;
        }
        if (player instanceof NumenPlayer) {
            source.sendFailure(Component.literal(failureMessage));
            return null;
        }
        return player;
    }

    private static String citizenState(CommandSourceStack source, CitizenRecord record) {
        if (record.logicalOwner().kind() != OwnerKind.SERVER) {
            NumenServerCompat.OwnershipCheck ownership =
                    NumenServerCompat.checkManagedOwnership(
                            source.getServer(), record.citizenId(), record.bodyOwnerId());
            if (ownership == NumenServerCompat.OwnershipCheck.MISMATCH) {
                return "mismatch";
            }
            if (ownership == NumenServerCompat.OwnershipCheck.MISSING) {
                return "missing";
            }
            return NumenServerCompat.findLiveManaged(
                            source.getServer(), record.citizenId(), record.bodyOwnerId()) == null
                    ? "dormant"
                    : "live";
        }
        Optional<String> quarantine = ServerCitizenLifecycleManager.instance()
                .quarantineReason(record.citizenId());
        if (quarantine.isPresent()) {
            return "quarantined";
        }
        return NumenServerCompat.inspectServerBody(
                        source.getServer(), record.citizenId(), record.bodyOwnerId())
                .status()
                .name()
                .toLowerCase(Locale.ROOT);
    }

    static boolean validCitizenName(String value) {
        return value != null && CITIZEN_NAME.matcher(value).matches();
    }

    static boolean validProfileIdentifier(String value) {
        return value != null && PROFILE_IDENTIFIER.matcher(value).matches();
    }

    static String formatHome(HomeAnchor home) {
        return String.format(
                Locale.ROOT,
                "%s@(%.1f, %.1f, %.1f)",
                home.dimension(), home.x(), home.y(), home.z());
    }

    private static boolean hasExplicitWorldPosition(CommandSourceStack source) {
        if (source.getEntity() != null) {
            return true;
        }
        // Minecraft's bare server-console source is exactly the overworld origin. An
        // `/execute positioned` source carries the explicit coordinates supplied by the OP.
        return !source.getPosition().equals(Vec3.ZERO);
    }

    private static String cleanMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ').strip();
    }
}
