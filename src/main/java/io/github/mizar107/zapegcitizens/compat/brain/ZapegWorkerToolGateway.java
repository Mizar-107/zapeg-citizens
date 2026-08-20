package io.github.mizar107.zapegcitizens.compat.brain;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ZapeG-owned deterministic worker primitives executed server-side.
 *
 * <p>{@code equip_best_tool} removes a whole class of planner micro-management:
 * instead of guessing which named tool to {@code equip_item}, the model states
 * the target block and the server deterministically equips the best carried
 * tool for it (or reports, machine-readably, that the block needs a tool the
 * citizen does not carry, or that bare hands are fine). Pure vanilla inventory
 * manipulation on the server thread; no Numen internals are touched.
 */
public final class ZapegWorkerToolGateway {

    public static final String EQUIP_BEST_TOOL = "equip_best_tool";

    /** Main-inventory slots (hotbar + backpack) precede armor/offhand slots. */
    private static final int MAIN_INVENTORY_SLOTS = 36;

    private ZapegWorkerToolGateway() {}

    public static boolean handles(String toolName) {
        return EQUIP_BEST_TOOL.equals(toolName);
    }

    /** OpenAI/Ollama-compatible definition mirroring {@link NumenSkillGateway}. */
    public static JsonObject toolDefinition() {
        JsonObject blockId = new JsonObject();
        blockId.addProperty("type", "string");
        blockId.addProperty("description",
                "Exact block id the citizen is about to break or harvest, e.g. "
                        + "minecraft:oak_log or minecraft:deepslate_iron_ore.");

        JsonObject properties = new JsonObject();
        properties.add("block_id", blockId);

        JsonArray required = new JsonArray();
        required.add("block_id");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", required);
        parameters.addProperty("additionalProperties", false);

        JsonObject function = new JsonObject();
        function.addProperty("name", EQUIP_BEST_TOOL);
        function.addProperty("description",
                "Deterministically equip the best tool already carried for breaking the "
                        + "given block id. Succeeds with the equipped item, or with 'hand' when "
                        + "punching works and no carried tool is faster. Fails with code "
                        + "missing_tool only when the block cannot drop items without a proper "
                        + "tool and none is carried (e.g. stone or ore without a pickaxe) - that "
                        + "is a real blocker to report with job_needs_input. Never asks the "
                        + "owner for optional tools such as an axe for logs.");
        function.add("parameters", parameters);

        JsonObject definition = new JsonObject();
        definition.addProperty("type", "function");
        definition.add("function", function);
        return definition;
    }

    /** Execute on the server thread against a live managed body. */
    public static String execute(NumenPlayer citizen, JsonObject args) {
        if (citizen == null) {
            throw new IllegalArgumentException("a live citizen body is required");
        }
        if (args == null || !args.has("block_id") || !args.get("block_id").isJsonPrimitive()
                || !args.getAsJsonPrimitive("block_id").isString()) {
            throw new IllegalArgumentException(EQUIP_BEST_TOOL + " requires a string block_id");
        }
        if (args.size() != 1) {
            throw new IllegalArgumentException(EQUIP_BEST_TOOL + " accepts only the block_id field");
        }
        String blockId = args.get("block_id").getAsString();
        ResourceLocation location = ResourceLocation.tryParse(blockId);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
            throw new IllegalArgumentException("unknown block id: " + blockId);
        }
        BlockState state = BuiltInRegistries.BLOCK.get(location).defaultBlockState();
        boolean needsTool = state.requiresCorrectToolForDrops();

        Inventory inventory = citizen.getInventory();
        int slots = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int bestSlot = -1;
        float bestSpeed = 1.0F;
        boolean bestCorrect = false;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            boolean correct = stack.isCorrectToolForDrops(state);
            boolean better = (correct && !bestCorrect)
                    || (correct == bestCorrect && speed > bestSpeed
                            && (correct || !needsTool || speed > 1.0F));
            if (better && (speed > 1.0F || (correct && needsTool))) {
                bestSlot = slot;
                bestSpeed = speed;
                bestCorrect = correct;
            }
        }

        if (bestSlot < 0 || (needsTool && !bestCorrect)) {
            if (needsTool) {
                return failure("missing_tool",
                        "no carried tool harvests " + blockId
                                + "; it drops nothing without the proper tool (for stone or "
                                + "ore that means a pickaxe). This is a real blocker: report "
                                + "exactly what is needed with job_needs_input.");
            }
            return success(blockId, "hand", -1, 1.0F, true,
                    "no carried tool improves on the empty hand; punching works for this block");
        }

        int selected = inventory.selected;
        String equippedId = BuiltInRegistries.ITEM
                .getKey(inventory.getItem(bestSlot).getItem()).toString();
        if (bestSlot != selected) {
            ItemStack best = inventory.getItem(bestSlot);
            ItemStack current = inventory.getItem(selected);
            inventory.setItem(selected, best);
            inventory.setItem(bestSlot, current);
            inventory.setChanged();
        }
        return success(blockId, equippedId, selected, bestSpeed, bestCorrect,
                bestSlot == selected
                        ? "the best tool was already in the active hand"
                        : "equipped the best carried tool into the active hand");
    }

    private static String success(
            String blockId,
            String equipped,
            int slot,
            float speed,
            boolean correctForDrops,
            String message) {
        JsonObject data = new JsonObject();
        data.addProperty("block_id", blockId);
        data.addProperty("equipped", equipped);
        if (slot >= 0) {
            data.addProperty("hotbar_slot", slot);
        }
        data.addProperty("dig_speed", speed);
        data.addProperty("correct_for_drops", correctForDrops);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("message", message);
        result.add("data", data);
        return result.toString();
    }

    private static String failure(String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("code", code);
        result.addProperty("message", message);
        return result.toString();
    }
}
