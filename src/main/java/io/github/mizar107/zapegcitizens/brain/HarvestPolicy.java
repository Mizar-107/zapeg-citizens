package io.github.mizar107.zapegcitizens.brain;

import io.github.mizar107.zapegcitizens.chat.TurkishFold;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Vanilla harvest rules the planner cannot treat as owner blockers.
 *
 * <p>Logs and similar hand-breakable blocks can be punched. An axe is optional
 * speed, never a requirement. Stone and ore still need a pickaxe.
 *
 * <p>The keyword nets are bilingual (the live server speaks Turkish): every
 * English family carries its Turkish synonyms ("balta" for axe, "kürek" for
 * shovel, "makas" for shears, "odun/kütük" for wood/logs). Matching runs over
 * {@link TurkishFold#fold} output so {@code İ}/{@code I}/{@code ı} all compare
 * as a plain {@code i}, and the patterns are written in that folded form.
 */
public final class HarvestPolicy {

    static final String HAND_HARVEST_ANSWER =
            "Wood and other hand-breakable blocks can be punched. Do not wait for an "
                    + "axe. Call mine on the log or wood block ids now. Equip an axe only if "
                    + "one is already in inventory.";

    private static final int KEYWORD_FLAGS = Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern WOOD_GOAL = Pattern.compile(
            "\\b(wood|logs?|trees?|chop|lumber|timber|"
                    + "oak|spruce|birch|jungle|acacia|dark.?oak|mangrove|cherry|"
                    + "crimson|warped|hyphae|stems?|"
                    + "odun\\w*|kütü(?:k|ğ)\\w*|kutuk\\w*|ağa(?:c|ç)\\w*|agac\\w*|"
                    + "kereste\\w*|tahta\\w*)\\b",
            KEYWORD_FLAGS);
    private static final Pattern OPTIONAL_TOOL = Pattern.compile(
            "\\b(axes?|hatchets?|shovels?|shears?|"
                    + "balta\\w*|kürek\\w*|kurek\\w*|makas\\w*|nacak\\w*)\\b",
            KEYWORD_FLAGS);
    private static final Pattern PICKAXE = Pattern.compile(
            "\\b(pickaxes?|kazma\\w*)\\b", KEYWORD_FLAGS);
    private static final Pattern PUNCHABLE_QUESTION = Pattern.compile(
            "\\b(wood|logs?|trees?|leaves?|dirt|sand|gravel|punch|"
                    + "odun\\w*|kütü(?:k|ğ)\\w*|kutuk\\w*|ağa(?:c|ç)\\w*|agac\\w*|"
                    + "yaprak\\w*|toprak\\w*|kum\\w*|çakil\\w*|cakil\\w*|yumruk\\w*)\\b",
            KEYWORD_FLAGS);

    private HarvestPolicy() {}

    public static boolean isOptionalHarvestToolRequest(String goal, String question) {
        String goalText = TurkishFold.fold(goal);
        String questionText = TurkishFold.fold(question);
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
