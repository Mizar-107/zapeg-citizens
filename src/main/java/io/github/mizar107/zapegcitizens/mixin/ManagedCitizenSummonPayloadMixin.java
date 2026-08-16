package io.github.mizar107.zapegcitizens.mixin;

import com.dwinovo.numen.network.payload.SummonRequestPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Make OP-controlled /citizen provisioning the only companion creation path. */
@Mixin(value = SummonRequestPayload.class, remap = false)
abstract class ManagedCitizenSummonPayloadMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void zapegCitizens$rejectStockSummon(
            SummonRequestPayload payload, ServerPlayer player, CallbackInfo callback) {
        player.sendSystemMessage(Component.literal(
                "[Citizens] Citizens are provisioned by operators with /citizen spawn."));
        callback.cancel();
    }
}
