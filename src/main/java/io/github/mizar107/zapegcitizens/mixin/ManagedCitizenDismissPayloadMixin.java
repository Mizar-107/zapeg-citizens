package io.github.mizar107.zapegcitizens.mixin;

import com.dwinovo.numen.network.payload.DismissRequestPayload;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent Numen's client panel from permanently deleting an addon-managed body. */
@Mixin(value = DismissRequestPayload.class, remap = false)
abstract class ManagedCitizenDismissPayloadMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void zapegCitizens$protectManagedBody(
            DismissRequestPayload payload, ServerPlayer player, CallbackInfo callback) {
        if (payload.uuid() == null || CitizenRegistryData.get(player.server)
                .findByCitizenId(payload.uuid()).isEmpty()) {
            return;
        }
        player.sendSystemMessage(Component.literal(
                "[Citizens] Managed citizens can only be removed by an operator."));
        callback.cancel();
    }
}
