package io.github.mizar107.zapegcitizens.compat.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerToolPolicyTest {

    @Test
    void includesCoreWorkerCapabilities() {
        assertTrue(WorkerToolPolicy.isAllowed("goto"));
        assertTrue(WorkerToolPolicy.isAllowed("mine"));
        assertTrue(WorkerToolPolicy.isAllowed("craft"));
        assertTrue(WorkerToolPolicy.isAllowed("scan_blocks"));
        assertTrue(WorkerToolPolicy.isAllowed("get_self_status"));
    }

    @Test
    void excludesDestructiveAndUnboundedCapabilities() {
        assertFalse(WorkerToolPolicy.isAllowed("build"));
        assertFalse(WorkerToolPolicy.isAllowed("melee_attack"));
        assertFalse(WorkerToolPolicy.isAllowed("ranged_attack"));
        assertFalse(WorkerToolPolicy.isAllowed("drop_items"));
        assertFalse(WorkerToolPolicy.isAllowed("take_items"));
        assertFalse(WorkerToolPolicy.isAllowed("transfer"));
        assertFalse(WorkerToolPolicy.isAllowed("interact_at"));
        assertFalse(WorkerToolPolicy.isAllowed("interact_entity"));
        assertFalse(WorkerToolPolicy.isAllowed("inspect_block_storage"));
        assertFalse(WorkerToolPolicy.isAllowed("todowrite"));
        assertFalse(WorkerToolPolicy.isAllowed("load_skill"));
    }
}
