package io.github.mizar107.zapegcitizens.network;

import io.github.mizar107.zapegcitizens.ZapeGCitizens;
import io.github.mizar107.zapegcitizens.network.packet.CitizenPromptS2C;
import io.github.mizar107.zapegcitizens.network.packet.CitizenReadyS2C;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.UUID;

public final class CitizenNetwork {

    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(ZapeGCitizens.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private static int nextMessageId;

    private CitizenNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(
                nextMessageId++,
                CitizenReadyS2C.class,
                CitizenReadyS2C::encode,
                CitizenReadyS2C::decode,
                CitizenReadyS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(
                nextMessageId++,
                CitizenPromptS2C.class,
                CitizenPromptS2C::encode,
                CitizenPromptS2C::decode,
                CitizenPromptS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendReady(ServerPlayer owner, UUID citizenId, String name) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner), new CitizenReadyS2C(citizenId, name));
    }

    public static void sendPrompt(ServerPlayer owner, UUID citizenId, String name, String prompt) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner),
                new CitizenPromptS2C(citizenId, name, prompt));
    }
}
