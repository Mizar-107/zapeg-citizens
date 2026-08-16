package io.github.mizar107.zapegcitizens.compat.brain;

import java.util.List;
import java.util.Set;

/**
 * Fail-closed capability policy for the shared worker brain.
 *
 * <p>These are the exact tool names exported by pinned Numen 0.1.1. Keep this
 * list explicit: newly installed or newly added Numen tools must never become
 * available to a remote model merely because they appeared in ToolRegistry.
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
            "craft"
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
