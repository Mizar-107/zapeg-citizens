"""Server-side rules so a durable job keeps the original instruction.

The planner cannot finish after the first successful mine, or pause to ask the
actor to re-issue an implied later step such as craft, deposit, or continue.

The keyword nets are bilingual (the live server speaks Turkish): storage goals
also match "sandık/depo" forms, craft goals also match "üret/craftla", and the
re-issue net also matches "devam de", "... edeyim mi", and "seni bekliyorum"
phrasings. Matching runs over :func:`citizen_brain.textfold.fold` output, so
``İ``/``I``/``ı`` all compare as a plain ``i`` and the patterns are written in
that folded form (``sandik``, ``hazir``).
"""

from __future__ import annotations

import re

from .textfold import fold

PREMATURE_FINISH_FAULT = (
    "job_finish is rejected because the original instruction still has implied "
    "later steps that are not proven. Call the next remaining allowlisted world "
    "tool now; do not wait for another player command."
)

SEQUENCE_REISSUE_FAULT = (
    "do not pause for the actor to re-issue an implied next step such as craft, "
    "deposit, or continue; call the next remaining world tool toward the original "
    "instruction"
)

STORAGE_MOVE_TOOLS = frozenset(
    {
        "transfer",
        "interact_at",
        "inspect_gui",
        "inspect_block_storage",
        "take_items",
        "goto",
    }
)
CRAFT_TOOLS = frozenset({"craft"})

_STORAGE_GOAL = re.compile(
    r"\b(chests?|barrels?|shulkers?|containers?|deposit|store|stash|"
    r"put (it|them|this|those|the \w+) (in|into|inside)|"
    r"sandi(?:k|ğ|g)\w*|depo\w*|fiçi\w*|fici\w*|istifle\w*)\b",
    re.IGNORECASE,
)
_CRAFT_GOAL = re.compile(
    r"\b(craft(s|ing|ed)?|craftla\w*|üret\w*|uret\w*)\b", re.IGNORECASE
)
_REISSUE = re.compile(
    r"\b("
    r"now (continue|craft|deposit|put|store)|"
    r"say continue|"
    r"tell me to|"
    r"waiting for (you|the (owner|player))|"
    r"should i (continue|proceed|put|deposit|craft|store|keep going)|"
    r"do you want me to (continue|put|deposit|craft)|"
    r"ready for (the )?next|"
    r"devam (de|der misin|dersen|komutu)|"
    r"devam etmemi (söyle|soyle|iste|ister)\w*|"
    r"(devam|koymami|craftlamami|depolamami) ister misin\w*|"
    r"(edeyim|koyayim|yapayim|craftlayayim|depolayayim|başlayayim|baslayayim) mi\b|"
    r"(seni|onayini|komutunu?) bekliyorum|"
    r"sonraki (adim|görev|gorev)[^.]{0,12}hazir\w*"
    r")\b",
    re.IGNORECASE,
)


def _successful_names(confirmed_actions: list[dict] | None) -> set[str]:
    names: set[str] = set()
    for item in confirmed_actions or ():
        if not item.get("success"):
            continue
        name = item.get("action_name")
        if isinstance(name, str) and name:
            names.add(name)
    return names


def premature_finish_fault(
    goal: str | None, confirmed_actions: list[dict] | None
) -> str | None:
    """Reject job_finish when implied later steps still lack a matching action."""
    goal_text = fold(goal)
    names = _successful_names(confirmed_actions)
    if _STORAGE_GOAL.search(goal_text) and names.isdisjoint(STORAGE_MOVE_TOOLS):
        return PREMATURE_FINISH_FAULT
    if _CRAFT_GOAL.search(goal_text) and names.isdisjoint(CRAFT_TOOLS):
        return PREMATURE_FINISH_FAULT
    return None


def sequence_reissue_fault(goal: str | None, question: str | None) -> str | None:
    """Reject job_needs_input that asks the actor to re-issue an implied step."""
    _ = goal
    if _REISSUE.search(fold(question)):
        return SEQUENCE_REISSUE_FAULT
    return None
