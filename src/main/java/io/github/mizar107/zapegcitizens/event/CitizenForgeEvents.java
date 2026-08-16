package io.github.mizar107.zapegcitizens.event;

import com.dwinovo.numen.entity.NumenPlayer;
import io.github.mizar107.zapegcitizens.ZapeGCitizens;
import io.github.mizar107.zapegcitizens.chat.CitizenChatAddress;
import io.github.mizar107.zapegcitizens.command.CitizenCommands;
import io.github.mizar107.zapegcitizens.compat.NumenServerCompat;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import io.github.mizar107.zapegcitizens.network.CitizenNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
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
    public static void onServerChat(ServerChatEvent event) {
        CitizenChatAddress.parse(event.getRawText()).ifPresent(address -> route(event, address));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof NumenPlayer)) {
            LAST_TASK_TICK.remove(player.getUUID());
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

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
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
                .filter(candidate -> candidate.ownerId().equals(owner.getUUID()))
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

        long now = owner.server.overworld().getGameTime();
        long previous = LAST_TASK_TICK.getOrDefault(owner.getUUID(), Long.MIN_VALUE / 2);
        if (now - previous < CHAT_COOLDOWN_TICKS) {
            owner.sendSystemMessage(Component.literal(
                    "[Citizens] Wait two seconds before sending another task."));
            return;
        }
        LAST_TASK_TICK.put(owner.getUUID(), now);

        CitizenNetwork.sendPrompt(
                owner, citizen.getUUID(), citizen.getGameProfile().getName(), address.prompt());
    }
}
