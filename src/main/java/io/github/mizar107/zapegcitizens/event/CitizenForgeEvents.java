package io.github.mizar107.zapegcitizens.event;

import com.dwinovo.numen.entity.NumenPlayer;
import io.github.mizar107.zapegcitizens.ZapeGCitizens;
import io.github.mizar107.zapegcitizens.brain.CitizenBrainCoordinator;
import io.github.mizar107.zapegcitizens.chat.CitizenChatAddress;
import io.github.mizar107.zapegcitizens.command.CitizenCommands;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat;
import io.github.mizar107.zapegcitizens.compat.brain.NumenToolGateway;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
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
                NumenPlayer citizen = NumenServerCompat.findLiveOwned(player, record.citizenId());
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
            CitizenBrainCoordinator.instance().bodyUnavailable(citizen, "it died");
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            CitizenBrainCoordinator.instance().expireTimedOutTurns(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CitizenBrainCoordinator.instance().shutdown(event.getServer());
        for (CitizenRegistryData.CitizenRecord record
                : CitizenRegistryData.get(event.getServer()).all()) {
            NumenPlayer citizen = NumenServerCompat.findLive(event.getServer(), record.citizenId());
            if (citizen != null) {
                NumenServerCompat.makeDormant(event.getServer(), citizen);
            }
        }
    }

    private static void route(ServerChatEvent event, CitizenChatAddress address) {
        // An explicitly addressed task is private even when it fails authorization/resolution.
        event.setCanceled(true);
        ServerPlayer owner = event.getPlayer();

        CitizenRegistryData.CitizenRecord record = CitizenRegistryData.get(owner.server)
                .findByName(address.citizenName())
                .filter(candidate -> candidate.logicalOwner().matchesPlayer(owner.getUUID()))
                .orElse(null);
        if (record == null) {
            owner.sendSystemMessage(Component.literal(
                    "[Citizens] You have no live citizen named '" + address.citizenName() + "'."));
            return;
        }

        NumenPlayer citizen = NumenServerCompat.findLiveOwned(owner, record.citizenId());
        if (citizen == null) {
            owner.sendSystemMessage(Component.literal(
                    "[Citizens] " + record.name() + " is currently dormant or respawning."));
            return;
        }

        if (isStop(address.prompt())) {
            CitizenBrainCoordinator.instance().stop(owner, record, citizen);
            return;
        }

        long now = owner.server.overworld().getGameTime();
        long previous = LAST_TASK_TICK.getOrDefault(owner.getUUID(), Long.MIN_VALUE / 2);
        if (now - previous < CHAT_COOLDOWN_TICKS) {
            owner.sendSystemMessage(Component.literal(
                    "[Citizens] Wait two seconds before sending another task."));
            return;
        }
        LAST_TASK_TICK.put(owner.getUUID(), now);

        CitizenBrainCoordinator.instance().submit(owner, record, citizen, address.prompt());
    }

    private static boolean isStop(String prompt) {
        String normalized = prompt.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("stop")
                || normalized.equals("cancel")
                || normalized.equals("dur")
                || normalized.equals("iptal");
    }
}
