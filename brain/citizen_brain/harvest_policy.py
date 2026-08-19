"""Server-side harvest rules the planner cannot override.

Vanilla players punch logs, dirt, sand, and similar blocks by hand. An axe is a
speed upgrade, not a capability gate. Stone and ore still need a pickaxe.
"""

from __future__ import annotations

import re

HAND_HARVEST_FAULT = (
    "wood and other hand-breakable blocks do not require a harvest tool; "
    "call mine with the log or wood block ids now. Punching with empty hands is "
    "allowed. Equip an axe only if one is already in inventory; never call "
    "job_needs_input for an axe."
)

_WOOD_GOAL = re.compile(
    r"\b(wood|logs?|trees?|chop|lumber|timber|"
    r"oak|spruce|birch|jungle|acacia|dark.?oak|mangrove|cherry|"
    r"crimson|warped|hyphae|stems?)\b",
    re.IGNORECASE,
)
_OPTIONAL_TOOL = re.compile(r"\b(axes?|hatchets?|shovels?|shears?)\b", re.IGNORECASE)
_PICKAXE_ONLY = re.compile(r"\bpickaxes?\b", re.IGNORECASE)
_PUNCHABLE_QUESTION = re.compile(
    r"\b(wood|logs?|trees?|leaves?|dirt|sand|gravel|punch)\b",
    re.IGNORECASE,
)


def optional_harvest_tool_fault(goal: str | None, question: str | None) -> str | None:
    """Return planner-fault text when job_needs_input asks for a punch-optional tool."""
    goal_text = goal or ""
    question_text = question or ""
    if not _OPTIONAL_TOOL.search(question_text):
        return None
    if _PICKAXE_ONLY.search(question_text) and not _OPTIONAL_TOOL.search(
        _PICKAXE_ONLY.sub(" ", question_text)
    ):
        return None
    if _WOOD_GOAL.search(goal_text) or _PUNCHABLE_QUESTION.search(question_text):
        return HAND_HARVEST_FAULT
    return None
