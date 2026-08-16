package io.github.mizar107.zapegcitizens.mixin;

import com.dwinovo.numen.task.TaskQueue;
import com.dwinovo.numen.task.TaskRecord;
import io.github.mizar107.zapegcitizens.compat.brain.NumenToolGateway;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Compatibility hook for the exact pinned Numen task queue. */
@Mixin(value = TaskQueue.class, remap = false)
abstract class TaskQueueResultMixin {

    @Inject(method = "complete", at = @At("TAIL"), remap = false)
    private void zapegCitizens$captureTerminalResult(TaskRecord record, CallbackInfo callback) {
        NumenToolGateway.onTaskCompleted(record);
    }
}
