package io.github.mizar107.zapegcitizens.compat.brain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void exportsAllServerCapableNumenToolsInStableOrder() {
        assertEquals(List.of(
                "get_self_status",
                "get_owner_status",
                "get_world_info",
                "look_around",
                "scan_nearby_entities",
                "scan_blocks",
                "inspect_block",
                "locate_structure",
                "locate_biome",
                "lookup_recipe",
                "goto",
                "mine",
                "collect_items",
                "fish",
                "equip_item",
                "eat_item",
                "craft",
                "build",
                "blueprint",
                "blueprint_read",
                "interact_at",
                "interact_entity",
                "melee_attack",
                "ranged_attack",
                "task_status",
                "task_stop",
                "drop_items",
                "take_items",
                "inspect_gui",
                "transfer",
                "close_gui",
                "inspect_block_storage"
        ), WorkerToolPolicy.orderedNames());

        assertTrue(WorkerToolPolicy.isAllowed("build"));
        assertTrue(WorkerToolPolicy.isAllowed("blueprint"));
        assertTrue(WorkerToolPolicy.isAllowed("melee_attack"));
        assertTrue(WorkerToolPolicy.isAllowed("ranged_attack"));
        assertTrue(WorkerToolPolicy.isAllowed("drop_items"));
        assertTrue(WorkerToolPolicy.isAllowed("take_items"));
        assertTrue(WorkerToolPolicy.isAllowed("transfer"));
        assertTrue(WorkerToolPolicy.isAllowed("interact_at"));
        assertTrue(WorkerToolPolicy.isAllowed("interact_entity"));
        assertTrue(WorkerToolPolicy.isAllowed("inspect_block_storage"));
    }

    @Test
    void classifiesExactlyTheTwoAttackToolsForThePlayerTargetGuard() {
        assertTrue(WorkerToolPolicy.isAttackTool("melee_attack"));
        assertTrue(WorkerToolPolicy.isAttackTool("ranged_attack"));

        assertFalse(WorkerToolPolicy.isAttackTool("mine"));
        assertFalse(WorkerToolPolicy.isAttackTool("interact_entity"));
        assertFalse(WorkerToolPolicy.isAttackTool("task_stop"));
        assertFalse(WorkerToolPolicy.isAttackTool(null));
    }

    @Test
    void excludesOnlyClientOnlyAndUnknownTools() {
        // Numen implements these two as client-side planning/skill helpers; the
        // dedicated-server bridge has no client context in which to run them.
        assertFalse(WorkerToolPolicy.isAllowed("todowrite"));
        assertFalse(WorkerToolPolicy.isAllowed("load_skill"));
        assertFalse(WorkerToolPolicy.isAllowed("future_numen_tool"));
        assertFalse(WorkerToolPolicy.isAllowed(null));
    }
}
