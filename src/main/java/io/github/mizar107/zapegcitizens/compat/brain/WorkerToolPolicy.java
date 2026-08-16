package io.github.mizar107.zapegcitizens.compat.brain;

import java.util.List;
import java.util.Set;

/**
 * Explicit server-capable Numen tool policy for the shared worker brain.
 *
 * <p>These are all 32 server-capable tool names exported by pinned Numen 0.1.1.
 * Keep this list explicit so a Numen upgrade cannot silently expose a new tool.
 * The remaining registered tools, {@code todowrite} and {@code load_skill}, are
 * client-only helpers and cannot execute through the dedicated-server bridge.
 */
public final class WorkerToolPolicy {

    private static final List<String> ORDERED_NAMES = List.of(
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
    );

    private static final Set<String> NAMES = Set.copyOf(ORDERED_NAMES);

    private WorkerToolPolicy() {}

    public static List<String> orderedNames() {
        return ORDERED_NAMES;
    }

    public static boolean isAllowed(String toolName) {
        return toolName != null && NAMES.contains(toolName);
    }
}
