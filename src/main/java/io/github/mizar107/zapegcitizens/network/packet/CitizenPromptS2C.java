package io.github.mizar107.zapegcitizens.network.packet;

import io.github.mizar107.zapegcitizens.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record CitizenPromptS2C(UUID citizenId, String name, String prompt) {

    public static void encode(CitizenPromptS2C message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.citizenId());
        buffer.writeUtf(message.name(), 16);
        buffer.writeUtf(message.prompt(), 256);
    }

    public static CitizenPromptS2C decode(FriendlyByteBuf buffer) {
        return new CitizenPromptS2C(buffer.readUUID(), buffer.readUtf(16), buffer.readUtf(256));
    }

    public static void handle(CitizenPromptS2C message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> ClientPacketHandlers.onCitizenPrompt(message)));
        context.setPacketHandled(true);
    }
}
