"""Deterministic staged templates for common citizen jobs.

A template decomposes one player request into an ordered list of checkpointed
stages. The stage machine is plain server code, never the model: stages advance
only on confirmed successful action evidence, each stage carries its own action
budget, and the whole structure is persisted with the job (SQLite
``jobs.template_json``) so restarts resume mid-stage. The LLM plans freely
*within* the current stage; it cannot skip stages, and ``job_finish`` stays
rejected until the final stage's evidence exists.

Detection is conservative: an explicit ``template:<name> key=value`` prefix
always wins, a small set of natural English/Turkish phrasings is recognized,
and anything else stays a freeform job with unchanged behavior.
"""

from __future__ import annotations

import json
import re
from typing import Any, Mapping

# Version 2 added the "inventory_delta" advance kind (quantity-gated stages).
# Evaluation dispatches on each stage's advance.kind, so persisted version-1
# templates with only "successful_action" stages keep working unchanged.
TEMPLATE_VERSION = 2

# Ore families the mine_ore template knows how to translate into exact block ids.
ORE_BLOCKS: dict[str, list[str]] = {
    "iron": ["minecraft:iron_ore", "minecraft:deepslate_iron_ore"],
    "coal": ["minecraft:coal_ore", "minecraft:deepslate_coal_ore"],
    "copper": ["minecraft:copper_ore", "minecraft:deepslate_copper_ore"],
    "gold": ["minecraft:gold_ore", "minecraft:deepslate_gold_ore"],
    "diamond": ["minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"],
    "emerald": ["minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"],
    "redstone": ["minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"],
    "lapis": ["minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"],
}

# Inventory item ids that count as possession evidence per mined ore family:
# the ordinary drop plus the ore blocks themselves (silk touch / raw blocks).
ORE_DROP_ITEMS: dict[str, list[str]] = {
    "iron": ["minecraft:raw_iron", *ORE_BLOCKS["iron"]],
    "coal": ["minecraft:coal", *ORE_BLOCKS["coal"]],
    "copper": ["minecraft:raw_copper", *ORE_BLOCKS["copper"]],
    "gold": ["minecraft:raw_gold", *ORE_BLOCKS["gold"]],
    "diamond": ["minecraft:diamond", *ORE_BLOCKS["diamond"]],
    "emerald": ["minecraft:emerald", *ORE_BLOCKS["emerald"]],
    "redstone": ["minecraft:redstone", *ORE_BLOCKS["redstone"]],
    "lapis": ["minecraft:lapis_lazuli", *ORE_BLOCKS["lapis"]],
}

_TURKISH_ORES = {
    "demir": "iron",
    "kömür": "coal",
    "komur": "coal",
    "bakır": "copper",
    "bakir": "copper",
    "altın": "gold",
    "altin": "gold",
    "elmas": "diamond",
    "zümrüt": "emerald",
    "zumrut": "emerald",
    "redstone": "redstone",
    "lapis": "lapis",
}

# Small predefined survival-safe structures with exact bills of materials.
BLUEPRINTS: dict[str, dict[str, Any]] = {
    "shelter_hut": {
        "title": "small 5x5 oak shelter hut (4 high, slab roof)",
        "footprint": "5x5 blocks, walls 3 high plus a slab roof",
        "bill": {
            "minecraft:oak_planks": 96,
            "minecraft:oak_slab": 36,
            "minecraft:oak_door": 1,
            "minecraft:torch": 4,
        },
    },
    "storage_hut": {
        "title": "7x5 spruce storage hut with six chests",
        "footprint": "7x5 blocks, walls 3 high, slab roof, chest wall inside",
        "bill": {
            "minecraft:spruce_planks": 140,
            "minecraft:spruce_slab": 48,
            "minecraft:chest": 6,
            "minecraft:spruce_door": 1,
            "minecraft:torch": 6,
        },
    },
    "watchtower": {
        "title": "3x3 cobblestone watchtower, 10 high with ladder and fenced top",
        "footprint": "3x3 blocks, 10 high, internal ladder, fence rail on top",
        "bill": {
            "minecraft:cobblestone": 180,
            "minecraft:ladder": 10,
            "minecraft:oak_fence": 12,
            "minecraft:torch": 4,
        },
    },
}

_EXPLICIT = re.compile(
    r"^\s*template\s*:\s*(?P<name>[a-z_]+)\s*(?P<params>.*)$",
    re.IGNORECASE,
)
_PARAM = re.compile(r"([a-z_]+)\s*=\s*([A-Za-z0-9_:]+)", re.IGNORECASE)

_GATHER_WOOD_EN = re.compile(
    r"\b(?:gather|collect|chop|cut|get|bring|fetch)\b[^.]{0,40}?"
    r"\b(\d{1,4})\b[^.]{0,24}?\b(?:wood|logs?)\b",
    re.IGNORECASE,
)
_GATHER_WOOD_TR = re.compile(
    r"\b(\d{1,4})\s*(?:tane\s+)?(?:odun|kütük|kutuk)\b[^.]{0,40}?"
    r"\b(?:topla|kes|getir|kır|kir)\w*",
    re.IGNORECASE,
)
_MINE_ORE_EN = re.compile(
    r"\bmine\b[^.]{0,40}?\b(\d{1,4})\b\s*"
    r"(iron|coal|copper|gold|diamond|emerald|redstone|lapis)(?:s|es)?(?:\s+ores?)?\b",
    re.IGNORECASE,
)
_MINE_ORE_TR = re.compile(
    r"\b(\d{1,4})\s*(?:tane\s+)?"
    r"(demir|kömür|komur|bakır|bakir|altın|altin|elmas|zümrüt|zumrut|redstone|lapis)"
    r"(?:\s*cevheri)?\b[^.]{0,40}?\b(?:kaz|çıkar|cikar|getir)\w*",
    re.IGNORECASE,
)
_BUILD_EN = re.compile(
    r"\b(?:build|construct|make)\b[^.]{0,48}?"
    r"\b(shelter hut|shelter|storage hut|watch\s?tower)\b",
    re.IGNORECASE,
)
_BUILD_TR = re.compile(
    r"\b(barınak|barinak|depo kulübesi|depo kulubesi|gözetleme kulesi|"
    r"gozetleme kulesi|gözcü kulesi|gozcu kulesi)\b[^.]{0,48}?"
    r"\b(?:yap|kur|inşa|insa)\w*",
    re.IGNORECASE,
)

_BUILD_PHRASES = {
    "shelter hut": "shelter_hut",
    "shelter": "shelter_hut",
    "barınak": "shelter_hut",
    "barinak": "shelter_hut",
    "storage hut": "storage_hut",
    "depo kulübesi": "storage_hut",
    "depo kulubesi": "storage_hut",
    "watchtower": "watchtower",
    "watch tower": "watchtower",
    "gözetleme kulesi": "watchtower",
    "gozetleme kulesi": "watchtower",
    "gözcü kulesi": "watchtower",
    "gozcu kulesi": "watchtower",
}


def _clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def _stage(
    name: str,
    goal: str,
    advance_names: list[str],
    *,
    count: int = 1,
    max_actions: int = 8,
    strict: bool = False,
) -> dict[str, Any]:
    """One checkpointed stage.

    ``strict`` demands evidence recorded strictly after the previous stage's
    satisfaction point. Use it when consecutive stages share an advance action
    (three ``build`` stages need three distinct successful builds); non-strict
    stages honor a legitimate jump-ahead where one event proves several stages.
    """
    return {
        "name": name,
        "goal": goal,
        "max_actions": max_actions,
        "advance": {
            "kind": "successful_action",
            "names": advance_names,
            "count": count,
            "strict": strict,
        },
    }


def _delta_stage(
    name: str,
    goal: str,
    items: list[str],
    *,
    count: int,
    max_actions: int = 8,
) -> dict[str, Any]:
    """One quantity-gated stage.

    The stage exits only on deterministic possession evidence: confirmed
    successful results must show that the citizen gained at least ``count``
    matching items since the stage began (see ``_delta_condition_satisfied_at``).
    ``items`` entries are exact ids (``minecraft:coal``) or family suffixes
    (``_log``).
    """
    return {
        "name": name,
        "goal": goal,
        "max_actions": max_actions,
        "advance": {
            "kind": "inventory_delta",
            "items": list(items),
            "count": count,
        },
    }


def _wrap(name: str, params: dict[str, Any], stages: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "version": TEMPLATE_VERSION,
        "name": name,
        "params": params,
        "stage_index": 0,
        "stage_started_actions": 0,
        "stage_started_event": 0,
        "completed": False,
        "stages": stages,
    }


def _gather_wood_template(count: int) -> dict[str, Any]:
    count = _clamp(count, 1, 256)
    return _wrap(
        "gather_wood",
        {"count": count},
        [
            _stage(
                "survey",
                "Check yourself and find nearby log blocks: call get_self_status, then "
                "scan_blocks or look_around for any '*_log' blocks. Wood is punchable "
                "with bare hands — never ask the owner for an axe. If an axe is already "
                "carried, equip_best_tool speeds things up.",
                # A successful mine also proves logs were found, so a planner
                # that legitimately jumps straight to chopping is not penalized.
                ["scan_blocks", "look_around", "mine"],
                max_actions=6,
            ),
            _delta_stage(
                "chop",
                f"Mine {count} NEW log blocks (the exact '*_log' ids observed nearby; "
                f"punching with empty hands is valid). Prefer one mine call with "
                f"count={count}; mine paths to targets and collects its own drops. Use "
                "collect_items only for leftover drops. The server advances this "
                f"stage only when confirmed inventory evidence shows {count} newly "
                "gained '*_log' items, so after mining call get_self_status to prove "
                "the new count; if it is short, keep mining.",
                ["_log"],
                count=count,
                max_actions=12,
            ),
            _stage(
                "verify_deliver",
                "Verify with get_self_status that the new logs are in inventory and note "
                "the exact count. If the original instruction also asked for delivery, "
                "deposit, or crafting, do that now (goto, interact_at, transfer, "
                "close_gui) before finishing.",
                ["get_self_status"],
                max_actions=10,
            ),
        ],
    )


def _mine_ore_template(ore: str, count: int) -> dict[str, Any]:
    count = _clamp(count, 1, 128)
    blocks = ORE_BLOCKS[ore]
    return _wrap(
        "mine_ore",
        {"ore": ore, "count": count},
        [
            _stage(
                "preflight",
                f"Call get_self_status. Stone and ore need a pickaxe: confirm a pickaxe "
                f"adequate for {ore} ore is in inventory (equip_best_tool with "
                f"block_id={blocks[0]} equips the best one). If no adequate pickaxe is "
                "carried, call job_needs_input immediately naming the exact pickaxe "
                "needed — do not search or mine without it.",
                # A successful ore mine also proves the pickaxe was adequate.
                ["get_self_status", "mine"],
                max_actions=6,
            ),
            _delta_stage(
                "mine",
                f"Mine {count} new {ore} ore. Use one mine call listing every variant id: "
                f"{', '.join(blocks)}. A no-target result means the loaded area is "
                "exhausted: move through bounded search sectors with goto, rescan with "
                "scan_blocks, and retry; state the blocker with job_needs_input if the "
                "area is truly exhausted. The server advances this stage only when "
                f"confirmed inventory evidence shows {count} newly gained {ore} "
                "drops/ore items, so after mining call get_self_status to prove the "
                "new count; if it is short, keep mining.",
                ORE_DROP_ITEMS[ore],
                count=count,
                max_actions=14,
            ),
            _stage(
                "verify_deliver",
                "Verify with get_self_status that the newly mined ore is in inventory and "
                "note the exact count gained. If the original instruction also asked for "
                "delivery, smelting, or crafting, do those steps now before finishing.",
                ["get_self_status"],
                max_actions=10,
            ),
        ],
    )


def _simple_build_template(blueprint: str) -> dict[str, Any]:
    spec = BLUEPRINTS[blueprint]
    bill_text = ", ".join(
        f"{count}x {item}" for item, count in sorted(spec["bill"].items())
    )
    return _wrap(
        "simple_build",
        {"blueprint": blueprint, "bill": spec["bill"]},
        [
            _stage(
                "survey",
                f"Survey the anchored site for a {spec['title']} ({spec['footprint']}). "
                "Use the actor submission anchor / look target as the site. Call "
                "look_around and inspect_block to confirm a workable, reasonably flat "
                "footprint. Call load_skill with name=building before designing.",
                ["look_around", "inspect_block", "scan_blocks"],
                max_actions=8,
            ),
            _stage(
                "materials",
                f"Call get_self_status and compare inventory against this exact bill of "
                f"materials: {bill_text}. In survival, build places NOTHING when any "
                "material is short. If anything is missing, call job_needs_input listing "
                "exactly the missing items and counts — the owner must supply them (they "
                "can toss items to the citizen; the job resumes automatically when the "
                "inventory changes). Only continue once every material is confirmed.",
                ["get_self_status"],
                max_actions=6,
            ),
            _stage(
                "foundation",
                f"Build the foundation: one full floor slab for the {spec['footprint']} "
                "footprint with one build call, then verify it with inspect_block.",
                ["build"],
                max_actions=8,
            ),
            _stage(
                "walls",
                "Build the walls above the floor with build ops: perimeter walls to full "
                "height, leaving a two-block-tall door opening; add windows if the "
                "blueprint allows.",
                ["build"],
                max_actions=10,
                strict=True,
            ),
            _stage(
                "roof",
                "Finish the structure: roof (slabs with a small overhang where the bill "
                "provides slabs), the door, torches for lighting, and any blueprint "
                "extras (chests, ladder, fence rail).",
                ["build"],
                max_actions=10,
                strict=True,
            ),
            _stage(
                "verify_handoff",
                "Verify the finished build: doors passable, roof closed, interior lit; "
                "inspect_block and look_around to confirm, patch only observed "
                "discrepancies with one-cell build set ops, then report handoff.",
                ["look_around", "inspect_block"],
                max_actions=10,
            ),
        ],
    )


def _explicit_template(goal: str) -> dict[str, Any] | None:
    match = _EXPLICIT.match(goal)
    if match is None:
        return None
    name = match.group("name").lower()
    params = {
        key.lower(): value for key, value in _PARAM.findall(match.group("params") or "")
    }

    def _int_param(*keys: str, default: int) -> int:
        for key in keys:
            raw = params.get(key)
            if raw is not None and raw.isdigit():
                return int(raw)
        return default

    if name == "gather_wood":
        return _gather_wood_template(_int_param("n", "count", default=16))
    if name == "mine_ore":
        ore = params.get("type", params.get("ore", "")).lower()
        if ore not in ORE_BLOCKS:
            return None
        return _mine_ore_template(ore, _int_param("n", "count", default=8))
    if name == "simple_build":
        blueprint = params.get("blueprint", "").lower()
        if blueprint not in BLUEPRINTS:
            return None
        return _simple_build_template(blueprint)
    return None


def detect_template(goal: str | None) -> dict[str, Any] | None:
    """Deterministically choose a staged template for a goal, or None."""
    if not goal:
        return None
    explicit = _explicit_template(goal)
    if explicit is not None:
        return explicit

    match = _GATHER_WOOD_EN.search(goal) or _GATHER_WOOD_TR.search(goal)
    if match is not None:
        return _gather_wood_template(int(match.group(1)))

    match = _MINE_ORE_EN.search(goal)
    if match is not None:
        return _mine_ore_template(match.group(2).lower(), int(match.group(1)))
    match = _MINE_ORE_TR.search(goal)
    if match is not None:
        ore = _TURKISH_ORES.get(match.group(2).lower())
        if ore is not None:
            return _mine_ore_template(ore, int(match.group(1)))

    match = _BUILD_EN.search(goal) or _BUILD_TR.search(goal)
    if match is not None:
        blueprint = _BUILD_PHRASES.get(" ".join(match.group(1).lower().split()))
        if blueprint is not None:
            return _simple_build_template(blueprint)
    return None


def current_stage(template: Mapping[str, Any]) -> dict[str, Any] | None:
    stages = template.get("stages")
    index = template.get("stage_index")
    if not isinstance(stages, list) or not isinstance(index, int):
        return None
    if 0 <= index < len(stages):
        return dict(stages[index])
    return None


def is_final_complete(template: Mapping[str, Any]) -> bool:
    return template.get("completed") is True


# Only these tools' successful results may carry possession evidence, and only
# inside inventory-context subtrees within them. A scan_blocks result listing
# visible world blocks must never count as items the citizen owns.
_DELTA_EVIDENCE_ACTIONS = frozenset(
    {"get_self_status", "mine", "collect_items", "take_items", "fish"}
)
_INVENTORY_CONTEXT_KEYS = frozenset(
    {
        "inventory",
        "items",
        "collected",
        "collected_items",
        "drops",
        "gained",
        "hotbar",
        "backpack",
        "main_inventory",
        "offhand",
    }
)
_ITEM_ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")


def _collect_item_counts(
    value: Any, counts: dict[str, int], in_context: bool
) -> None:
    if isinstance(value, dict):
        if in_context:
            identifier = value.get("id") or value.get("item") or value.get("name")
            amount = value.get("count", value.get("amount"))
            if (
                isinstance(identifier, str)
                and _ITEM_ID.fullmatch(identifier)
                and type(amount) is int
                and amount > 0
            ):
                counts[identifier] = counts.get(identifier, 0) + amount
        for key, sub in value.items():
            if (
                in_context
                and isinstance(key, str)
                and _ITEM_ID.fullmatch(key)
                and type(sub) is int
                and sub > 0
            ):
                counts[key] = counts.get(key, 0) + sub
            child_context = in_context or (
                isinstance(key, str) and key.lower() in _INVENTORY_CONTEXT_KEYS
            )
            if isinstance(sub, (dict, list)):
                _collect_item_counts(sub, counts, child_context)
    elif isinstance(value, list):
        for sub in value:
            _collect_item_counts(sub, counts, in_context)


def _extract_item_counts(result_json: Any) -> dict[str, int]:
    """Item-id → count evidence from one result payload.

    Handles both mapping form (``{"inventory": {"minecraft:oak_log": 6}}``) and
    record form (``{"collected": [{"item": "minecraft:oak_log", "count": 6}]}``).
    Item NBT is not represented in these payloads, so enchanted/named variants
    count as their plain id (the documented NBT-ignore limitation).
    """
    counts: dict[str, int] = {}
    _collect_item_counts(result_json, counts, False)
    return counts


def _inventory_extractions(
    confirmed_actions: list[Mapping[str, Any]],
) -> list[tuple[int, dict[str, int]]]:
    """Ordered (event_id, counts) pairs from evidence-bearing successful results."""
    extractions: list[tuple[int, dict[str, int]]] = []
    for item in confirmed_actions:
        if not item.get("success"):
            continue
        if item.get("action_name") not in _DELTA_EVIDENCE_ACTIONS:
            continue
        raw = item.get("result")
        if not isinstance(raw, str):
            continue
        try:
            parsed = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            continue
        counts = _extract_item_counts(parsed)
        if counts:
            extractions.append((int(item.get("event_id") or 0), counts))
    return extractions


def _item_matches(item_id: str, patterns: list[str]) -> bool:
    for pattern in patterns:
        if ":" in pattern:
            if item_id == pattern:
                return True
        elif item_id.endswith(pattern):
            return True
    return False


def _delta_condition_satisfied_at(
    stage: Mapping[str, Any],
    confirmed_actions: list[Mapping[str, Any]],
    started_event: int,
) -> int | None:
    """Quantity gate: (current - stage-start baseline) >= count for the family.

    The baseline is the latest extraction recorded at or before the stage's
    start; later extractions are candidates. Per item id only gains count
    (``max(0, current - baseline)``), so consuming one variant can never mint
    credit for another. With no pre-stage snapshot the baseline is empty and
    pre-owned items would count once observed — the survey/preflight stages
    therefore instruct an initial get_self_status, which establishes the
    baseline in the normal flow (documented limitation otherwise).
    """
    advance = stage.get("advance") or {}
    patterns = [
        pattern
        for pattern in (advance.get("items") or ())
        if isinstance(pattern, str) and pattern
    ]
    needed = int(advance.get("count") or 1)
    if not patterns:
        return None
    baseline: dict[str, int] = {}
    for event_id, counts in _inventory_extractions(confirmed_actions):
        if event_id <= started_event:
            baseline = counts
            continue
        gained = sum(
            max(0, count - baseline.get(item_id, 0))
            for item_id, count in counts.items()
            if _item_matches(item_id, patterns)
        )
        if gained >= needed:
            return event_id
    return None


def _condition_satisfied_at(
    stage: Mapping[str, Any],
    confirmed_actions: list[Mapping[str, Any]],
    started_event: int,
) -> int | None:
    """Return the event id at which the stage's exit condition was satisfied.

    Evidence ordering is non-strict (``>=``): when the planner legitimately
    jumps ahead (one successful ``mine`` both proves logs were found and chops
    them), the same event may satisfy consecutive stages, but evidence from
    before an earlier stage's satisfaction point can never satisfy a later one.
    """
    advance = stage.get("advance") or {}
    if advance.get("kind") == "inventory_delta":
        return _delta_condition_satisfied_at(stage, confirmed_actions, started_event)
    if advance.get("kind") != "successful_action":
        return None
    names = set(advance.get("names") or ())
    needed = int(advance.get("count") or 1)
    strict = advance.get("strict") is True
    hits = 0
    for item in confirmed_actions:
        event_id = int(item.get("event_id") or 0)
        in_window = event_id > started_event if strict else event_id >= started_event
        if item.get("success") and item.get("action_name") in names and in_window:
            hits += 1
            if hits >= needed:
                return event_id
    return None


def advance_stages(
    template: Mapping[str, Any],
    confirmed_actions: list[Mapping[str, Any]],
    actions_completed: int,
) -> tuple[dict[str, Any], bool]:
    """Advance through every satisfied stage; returns (template, advanced)."""
    updated = dict(template)
    advanced = False
    while not updated.get("completed"):
        stage = current_stage(updated)
        if stage is None:
            break
        started_event = int(updated.get("stage_started_event") or 0)
        satisfied_at = _condition_satisfied_at(stage, confirmed_actions, started_event)
        if satisfied_at is None:
            break
        advanced = True
        next_index = int(updated["stage_index"]) + 1
        if next_index >= len(updated["stages"]):
            updated["completed"] = True
            updated["stage_index"] = len(updated["stages"]) - 1
        else:
            updated["stage_index"] = next_index
        updated["stage_started_actions"] = actions_completed
        updated["stage_started_event"] = satisfied_at
    return updated, advanced


def _stage_limit(stage: Mapping[str, Any]) -> int:
    limit = stage.get("max_actions")
    return 8 if limit is None else int(limit)


def stage_budget_exhausted(template: Mapping[str, Any], actions_completed: int) -> bool:
    if template.get("completed"):
        return False
    stage = current_stage(template)
    if stage is None:
        return False
    used = actions_completed - int(template.get("stage_started_actions") or 0)
    return used >= _stage_limit(stage)


def rearm_stage_budget(
    template: Mapping[str, Any], actions_completed: int
) -> dict[str, Any]:
    updated = dict(template)
    updated["stage_started_actions"] = actions_completed
    return updated


def stage_context(template: Mapping[str, Any], actions_completed: int) -> dict[str, Any]:
    """Compact, model-visible description of the deterministic stage state."""
    stage = current_stage(template) or {}
    used = actions_completed - int(template.get("stage_started_actions") or 0)
    return {
        "template": template.get("name"),
        "stage": stage.get("name"),
        "stage_number": int(template.get("stage_index") or 0) + 1,
        "stage_total": len(template.get("stages") or ()),
        "stage_goal": stage.get("goal"),
        "stage_actions_used": max(0, used),
        "stage_actions_limit": _stage_limit(stage) if stage else 8,
        "final_stage_complete": is_final_complete(template),
        "rule": (
            "This job follows a fixed server-side template. Work ONLY on the current "
            "stage's goal; the server advances stages deterministically from confirmed "
            "successful results. job_finish stays rejected until the final stage is "
            "complete."
        ),
    }
