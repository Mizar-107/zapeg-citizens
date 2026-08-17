package io.github.mizar107.zapegcitizens.event;

import com.dwinovo.numen.entity.NumenPlayer;
import io.github.mizar107.zapegcitizens.ZapeGCitizens;
import io.github.mizar107.zapegcitizens.brain.CitizenBrainCoordinator;
import io.github.mizar107.zapegcitizens.brain.CitizenBrainCoordinator.InteractionMode;
import io.github.mizar107.zapegcitizens.brain.CitizenJobManager;
import io.github.mizar107.zapegcitizens.brain.CitizenJobManager.JobOperation;
import io.github.mizar107.zapegcitizens.chat.CitizenChatAddress;
import io.github.mizar107.zapegcitizens.command.CitizenCommands;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat;
import io.github.mizar107.zapegcitizens.compat.brain.NumenToolGateway;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobRecord;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobState;
import io.github.mizar107.zapegcitizens.lifecycle.ServerCitizenLifecycleManager;
import io.github.mizar107.zapegcitizens.lifecycle.ServerCitizenLifecycleManager.LifecycleResult;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = ZapeGCitizens.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CitizenForgeEvents {

    private static final long CHAT_COOLDOWN_TICKS = 40L;
    private static final Map<UUID, Long> LAST_TASK_TICK = new HashMap<>();

    private CitizenForgeEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CitizenCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        String command = event.getParseResults().getReader().getString().stripLeading();
        if (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        String normalized = command.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ");
        if (normalized.equals("numen player summon")
                || normalized.startsWith("numen player summon ")
                || normalized.equals("numen player despawn")
                || normalized.startsWith("numen player despawn ")) {
            event.setCanceled(true);
            event.getParseResults().getContext().getSource().sendFailure(Component.literal(
                    "Numen's stock lifecycle is disabled; use the operator /citizen commands."));
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        int tools = NumenToolGateway.verifyCompatibility();
        ZapeGCitizens.LOGGER.info(
                "Pinned Numen compatibility validated ({} worker tools)", tools);
        CitizenBrainCoordinator.instance().logConfiguration();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        for (LifecycleResult result
                : ServerCitizenLifecycleManager.instance().start(event.getServer())) {
            logLifecycleResult("startup", result);
        }
        int jobs = CitizenJobManager.instance().reconcile(event.getServer());
        ZapeGCitizens.LOGGER.info(
                "[citizen-job] startup reconciliation scheduled {} durable job(s)", jobs);
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        CitizenChatAddress.parse(event.getRawText()).ifPresent(address -> route(event, address));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof NumenPlayer citizen) {
            if (CitizenRegistryData.get(citizen.server)
                    .findByCitizenId(citizen.getUUID()).isPresent()) {
                CitizenBrainCoordinator.instance().bodyUnavailable(citizen, "its body went dormant");
            }
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof NumenPlayer)) {
            LAST_TASK_TICK.remove(player.getUUID());
            CitizenBrainCoordinator.instance().stopForLogout(player.server, player.getUUID());
            // Copy the persistent rows first because making a body dormant mutates Numen's
            // live player list and emits its own logout lifecycle.
            for (CitizenRegistryData.CitizenRecord record
                    : CitizenRegistryData.get(player.server).ownedBy(player.getUUID())) {
                NumenPlayer citizen = NumenServerCompat.findLiveManaged(
                        player.server, record.citizenId(), record.bodyOwnerId());
                if (citizen != null) {
                    NumenServerCompat.makeDormant(player.server, citizen);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (event.getEntity() instanceof NumenPlayer citizen
                && CitizenRegistryData.get(citizen.server)
                        .findByCitizenId(citizen.getUUID()).isPresent()) {
            CitizenRegistryData.CitizenRecord record = CitizenRegistryData.get(citizen.server)
                    .findByCitizenId(citizen.getUUID())
                    .orElseThrow();
            CitizenBrainCoordinator.instance().bodyUnavailable(citizen, "it died");
            if (record.logicalOwner().kind() == CitizenRegistryData.OwnerKind.SERVER) {
                logLifecycleResult(
                        "death",
                        ServerCitizenLifecycleManager.instance().onDeath(
                                citizen.server, record));
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            CitizenBrainCoordinator.instance().expireTimedOutTurns(event.getServer());
            for (LifecycleResult result
                    : ServerCitizenLifecycleManager.instance().tick(event.getServer())) {
                logLifecycleResult("tick", result);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LAST_TASK_TICK.clear();
        CitizenBrainCoordinator.instance().shutdown(event.getServer());
        for (LifecycleResult result
                : ServerCitizenLifecycleManager.instance().shutdown(event.getServer())) {
            logLifecycleResult("shutdown", result);
        }
        for (CitizenRegistryData.CitizenRecord record
                : CitizenRegistryData.get(event.getServer()).all()) {
            if (record.logicalOwner().kind() == CitizenRegistryData.OwnerKind.SERVER) {
                continue;
            }
            NumenPlayer citizen = NumenServerCompat.findLiveManaged(
                    event.getServer(), record.citizenId(), record.bodyOwnerId());
            if (citizen != null) {
                NumenServerCompat.makeDormant(event.getServer(), citizen);
            }
        }
    }

    private static void route(ServerChatEvent event, CitizenChatAddress address) {
        ServerPlayer actor = event.getPlayer();
        CitizenRegistryData.CitizenRecord record = CitizenRegistryData.get(actor.server)
                .findByName(address.citizenName())
                .orElse(null);
        if (record == null) {
            // Unknown/private-looking addresses do not leak into ordinary public chat.
            event.setCanceled(true);
            actor.sendSystemMessage(Component.literal(
                    "[Citizens] No managed citizen named '" + address.citizenName() + "'."));
            return;
        }

        boolean serverOwned = record.logicalOwner().kind()
                == CitizenRegistryData.OwnerKind.SERVER;
        if (!serverOwned) {
            // Player worker tasks stay private, including authorization failures.
            event.setCanceled(true);
            if (!record.logicalOwner().matchesPlayer(actor.getUUID())) {
                actor.sendSystemMessage(Component.literal(
                        "[Citizens] '" + record.name() + "' is assigned to another player."));
                return;
            }
        }

        if (!serverOwned) {
            CitizenJobManager jobs = CitizenJobManager.instance();
            JobRecord activeJob = jobs.status(actor.server, record.citizenId()).orElse(null);
            if (activeJob != null && activeJob.state() == JobState.NEEDS_INPUT) {
                if (isStatus(address.prompt())) {
                    actor.sendSystemMessage(Component.literal(
                            "[Citizens] " + record.name() + ": " + jobs.formatStatus(activeJob)));
                    return;
                }
                if (isStop(address.prompt())) {
                    JobOperation operation = jobs.cancel(
                            actor.server, record, "canceled by its assigned player");
                    actor.sendSystemMessage(Component.literal(
                            "[Citizens] " + operation.message()));
                    return;
                }
                if (isResume(address.prompt())) {
                    actor.sendSystemMessage(Component.literal(
                            "[Citizens] "
                                    + activeJob.message().orElse(
                                            "This job still needs a concrete answer.")
                                    + " Reply with @" + record.name()
                                    + " answer <your answer>."));
                    return;
                }
                JobOperation operation = jobs.resume(
                        actor.server, record, answerText(address.prompt()));
                actor.sendSystemMessage(Component.literal(
                        "[Citizens] " + operation.message()));
                return;
            }
            if (isStatus(address.prompt())) {
                actor.sendSystemMessage(Component.literal(activeJob == null
                        ? "[Citizens] " + record.name() + " has no active job."
                        : "[Citizens] " + record.name() + ": " + jobs.formatStatus(activeJob)));
                return;
            }
            if (isStop(address.prompt())) {
                JobOperation operation = jobs.cancel(
                        actor.server, record, "canceled by its assigned player");
                actor.sendSystemMessage(Component.literal(
                        "[Citizens] " + operation.message()));
                return;
            }
            if (isResume(address.prompt()) && activeJob == null) {
                actor.sendSystemMessage(Component.literal(
                        "[Citizens] " + record.name() + " has no paused job to resume."));
                return;
            }
            if (activeJob != null) {
                if (isResume(address.prompt())) {
                    JobOperation operation = jobs.resume(actor.server, record, "");
                    actor.sendSystemMessage(Component.literal(
                            "[Citizens] " + operation.message()));
                    return;
                }
                actor.sendSystemMessage(Component.literal(
                        "[Citizens] " + record.name() + " already has "
                                + jobs.formatStatus(activeJob)
                                + ". Use @" + record.name() + " status or @"
                                + record.name() + " stop."));
                return;
            }
        }

        NumenPlayer citizen = NumenServerCompat.findLiveManaged(
                actor.server, record.citizenId(), record.bodyOwnerId());
        if (citizen == null) {
            actor.sendSystemMessage(Component.literal(
                    "[Citizens] " + record.name() + " is currently dormant or respawning."));
            return;
        }

        long now = actor.server.overworld().getGameTime();
        long previous = LAST_TASK_TICK.getOrDefault(actor.getUUID(), Long.MIN_VALUE / 2);
        if (now - previous < CHAT_COOLDOWN_TICKS) {
            actor.sendSystemMessage(Component.literal(
                    "[Citizens] Wait two seconds before sending another task."));
            return;
        }
        LAST_TASK_TICK.put(actor.getUUID(), now);

        if (serverOwned) {
            CitizenBrainCoordinator.instance().submit(
                    actor, record, citizen, address.prompt(), InteractionMode.DIALOGUE);
        } else {
            JobOperation operation = CitizenJobManager.instance().submit(
                    actor, record, citizen, address.prompt());
            if (!operation.successful()) {
                actor.sendSystemMessage(Component.literal(
                        "[Citizens] " + operation.message()));
            }
        }
    }

    private static boolean isStop(String prompt) {
        String normalized = prompt.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("stop")
                || normalized.equals("cancel")
                || normalized.equals("dur")
                || normalized.equals("iptal");
    }

    private static boolean isStatus(String prompt) {
        String normalized = prompt.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("status")
                || normalized.equals("progress")
                || normalized.equals("durum");
    }

    private static boolean isResume(String prompt) {
        String normalized = prompt.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("resume")
                || normalized.equals("continue")
                || normalized.equals("devam");
    }

    private static String answerText(String prompt) {
        String stripped = prompt.strip();
        String normalized = stripped.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("answer ")) {
            return stripped.substring("answer ".length()).strip();
        }
        if (normalized.startsWith("cevap ")) {
            return stripped.substring("cevap ".length()).strip();
        }
        return stripped;
    }

    private static void logLifecycleResult(String phase, LifecycleResult result) {
        if (result.successful()) {
            ZapeGCitizens.LOGGER.info(
                    "[citizen-lifecycle] phase={} citizen={} status={} detail={}",
                    phase, result.citizenId(), result.status(), result.message());
        } else {
            ZapeGCitizens.LOGGER.warn(
                    "[citizen-lifecycle] phase={} citizen={} status={} detail={}",
                    phase, result.citizenId(), result.status(), result.message());
        }
    }
}
