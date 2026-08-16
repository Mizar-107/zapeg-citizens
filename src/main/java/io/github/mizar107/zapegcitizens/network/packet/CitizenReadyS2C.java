package io.github.mizar107.zapegcitizens.network.packet;

import io.github.mizar107.zapegcitizens.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record CitizenReadyS2C(UUID citizenId, String name) {

    public static void encode(CitizenReadyS2C message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.citizenId());
        buffer.writeUtf(message.name(), 16);
    }

    public static CitizenReadyS2C decode(FriendlyByteBuf buffer) {
        return new CitizenReadyS2C(buffer.readUUID(), buffer.readUtf(16));
    }

    public static void handle(CitizenReadyS2C message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> ClientPacketHandlers.onCitizenReady(message)));
        context.setPacketHandled(true);
    }
}
