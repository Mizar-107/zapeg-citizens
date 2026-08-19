package io.github.mizar107.zapegcitizens.brain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Vanilla harvest rules the planner cannot treat as owner blockers.
 *
 * <p>Logs and similar hand-breakable blocks can be punched. An axe is optional
 * speed, never a requirement. Stone and ore still need a pickaxe.
 */
public final class HarvestPolicy {

    static final String HAND_HARVEST_ANSWER =
            "Wood and other hand-breakable blocks can be punched. Do not wait for an "
                    + "axe. Call mine on the log or wood block ids now. Equip an axe only if "
                    + "one is already in inventory.";

    private static final Pattern WOOD_GOAL = Pattern.compile(
            "\\b(wood|logs?|trees?|chop|lumber|timber|"
                    + "oak|spruce|birch|jungle|acacia|dark.?oak|mangrove|cherry|"
                    + "crimson|warped|hyphae|stems?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTIONAL_TOOL = Pattern.compile(
            "\\b(axes?|hatchets?|shovels?|shears?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PICKAXE = Pattern.compile(
            "\\bpickaxes?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUNCHABLE_QUESTION = Pattern.compile(
            "\\b(wood|logs?|trees?|leaves?|dirt|sand|gravel|punch)\\b",
            Pattern.CASE_INSENSITIVE);

    private HarvestPolicy() {}

    public static boolean isOptionalHarvestToolRequest(String goal, String question) {
        String goalText = goal == null ? "" : goal;
        String questionText = question == null ? "" : question;
        if (!OPTIONAL_TOOL.matcher(questionText).find()) {
            return false;
        }
        if (PICKAXE.matcher(questionText).find()
                && !OPTIONAL_TOOL.matcher(PICKAXE.matcher(questionText).replaceAll(" ")).find()) {
            return false;
        }
        return WOOD_GOAL.matcher(goalText).find()
                || PUNCHABLE_QUESTION.matcher(questionText).find();
    }

    public static String handHarvestAnswer() {
        return HAND_HARVEST_ANSWER;
    }

    static boolean describesAxeSupply(String inventoryChange) {
        if (inventoryChange == null || inventoryChange.isBlank()) {
            return false;
        }
        String normalized = inventoryChange.toLowerCase(Locale.ROOT);
        return normalized.contains("_axe") || normalized.contains(":axe");
    }
}
