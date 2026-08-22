"""Server-side harvest rules the planner cannot override.

Vanilla players punch logs, dirt, sand, and similar blocks by hand. An axe is a
speed upgrade, not a capability gate. Stone and ore still need a pickaxe.

The keyword nets are bilingual: the live server speaks Turkish, so every
English family carries its Turkish synonyms ("balta" for axe, "kürek" for
shovel, "makas" for shears, "çapa" for hoe, "orak" for sickle, "odun/kütük"
for wood/logs, "şeker kamışı/kamış" for sugar cane). Matching runs over
:func:`citizen_brain.textfold.fold` output, so ``İ``/``I``/``ı`` all compare as
a plain ``i`` and the patterns are written in that folded form.
"""

from __future__ import annotations

import re

from .textfold import fold

HAND_HARVEST_FAULT = (
    "wood, sugar cane, crops, and other hand-breakable blocks do not require "
    "a harvest tool; call mine with the exact block ids now. Punching with "
    "empty hands is allowed. Equip an axe only if one is already in inventory; "
    "never call job_needs_input for an axe, hoe, shovel, or shears."
)

_WOOD_GOAL = re.compile(
    r"\b(wood|logs?|trees?|chop|lumber|timber|"
    r"oak|spruce|birch|jungle|acacia|dark.?oak|mangrove|cherry|"
    r"crimson|warped|hyphae|stems?|"
    r"odun\w*|kütü(?:k|ğ)\w*|kutuk\w*|ağa(?:c|ç)\w*|agac\w*|kereste\w*|tahta\w*)\b",
    re.IGNORECASE,
)
_OPTIONAL_TOOL = re.compile(
    r"\b(axes?|hatchets?|shovels?|shears?|hoes?|sickles?|"
    r"balta\w*|kürek\w*|kurek\w*|makas\w*|nacak\w*|"
    r"çapa\w*|capa\w*|orak\w*|t[ıi]rm[ıi]k\w*)\b",
    re.IGNORECASE,
)

# Surface plants: hand-harvest is the ONLY correct move (the 2026-08-21 field
# failure was a citizen pausing a sugar-cane job to ask the owner for a çapa,
# then re-asking after the owner said to use bare hands).
_PLANT_GOAL = re.compile(
    r"\b(sugar\s*cane|canes?|reeds?|bamboo|crops?|wheat|carrots?|potato\w*|"
    r"beetroots?|melons?|pumpkins?|"
    r"[şs]eker\s*kam[ıi][şs]\w*|kam[ıi][şs]\w*|bambu\w*|ekin\w*|"
    r"bu[ğg]day\w*|havu[çc]\w*|patates\w*|pancar\w*|kavun\w*|karpuz\w*|"
    r"bal\s*kaba[ğg]\w*|hasat\w*)\b",
    re.IGNORECASE,
)
_PICKAXE_ONLY = re.compile(r"\b(pickaxes?|kazma\w*)\b", re.IGNORECASE)
_PUNCHABLE_QUESTION = re.compile(
    r"\b(wood|logs?|trees?|leaves?|dirt|sand|gravel|punch|"
    r"sugar\s*cane|canes?|reeds?|bamboo|crops?|wheat|"
    r"odun\w*|kütü(?:k|ğ)\w*|kutuk\w*|ağa(?:c|ç)\w*|agac\w*|yaprak\w*|"
    r"toprak\w*|kum\w*|çakil\w*|cakil\w*|yumruk\w*|"
    r"[şs]eker\s*kam[ıi][şs]\w*|kam[ıi][şs]\w*|bambu\w*|ekin\w*|"
    r"bu[ğg]day\w*|hasat\w*)\b",
    re.IGNORECASE,
)


def optional_harvest_tool_fault(goal: str | None, question: str | None) -> str | None:
    """Return planner-fault text when job_needs_input asks for a punch-optional tool."""
    goal_text = fold(goal)
    question_text = fold(question)
    if not _OPTIONAL_TOOL.search(question_text):
        return None
    if _PICKAXE_ONLY.search(question_text) and not _OPTIONAL_TOOL.search(
        _PICKAXE_ONLY.sub(" ", question_text)
    ):
        return None
    if (
        _WOOD_GOAL.search(goal_text)
        or _PLANT_GOAL.search(goal_text)
        or _PUNCHABLE_QUESTION.search(question_text)
    ):
        return HAND_HARVEST_FAULT
    return None
