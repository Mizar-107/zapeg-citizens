package io.github.mizar107.zapegcitizens.mixin;

import com.dwinovo.numen.network.payload.CancelTasksPayload;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent a stock owner client from cancelling a server-brain-managed task lane. */
@Mixin(value = CancelTasksPayload.class, remap = false)
abstract class ManagedCitizenCancelTasksPayloadMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void zapegCitizens$rejectClientCancellation(
            CancelTasksPayload payload, ServerPlayer player, CallbackInfo callback) {
        if (CitizenRegistryData.get(player.level().getServer())
                .findByCitizenId(payload.entityUuid())
                .isPresent()) {
            // The coordinator cancels directly through CompanionTickDispatcher;
            // only this client-to-server packet path is suppressed.
            callback.cancel();
        }
    }
}
