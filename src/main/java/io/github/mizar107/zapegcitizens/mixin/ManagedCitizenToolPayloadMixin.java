package io.github.mizar107.zapegcitizens.mixin;

import com.dwinovo.numen.network.payload.ExecuteToolPayload;
import com.dwinovo.numen.network.payload.TaskResultPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import io.github.mizar107.zapegcitizens.data.CitizenRegistryData;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent an owner's stock client-side Numen brain from racing the shared brain. */
@Mixin(value = ExecuteToolPayload.class, remap = false)
abstract class ManagedCitizenToolPayloadMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void zapegCitizens$rejectClientControl(
            ExecuteToolPayload payload, ServerPlayer player, CallbackInfo callback) {
        if (CitizenRegistryData.get(player.level().getServer())
                .findByCitizenId(payload.entityUuid())
                .isEmpty()) {
            return;
        }
        // Do not call ExecuteToolPayload.replyError here: pinned Numen logs the
        // complete untrusted argumentsJson on that path. A quiet structured
        // rejection keeps the stock client loop consistent without creating a
        // 16 KiB-per-packet log-spam primitive.
        String result = TaskResult.fail(
                "managed citizen control is reserved for the server brain").toJson();
        Services.NETWORK.sendToPlayer(player, new TaskResultPayload(
                payload.entityUuid(), payload.toolCallId(), result));
        callback.cancel();
    }
}
