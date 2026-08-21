"""Locale-safe lowercase folding for the Turkish/English keyword nets.

``str.lower`` alone is not enough on a Turkish server: ``"İPTAL".lower()``
yields ``"i̇ptal"`` (an ``i`` plus a combining dot, U+0307) which never equals
``"iptal"``, and sloppy ASCII typing writes dotless ``ı`` words with a plain
``i``. This fold maps every capital/dotless ``i`` variant (``İ I ı i̇``) to a
plain ``i`` before ordinary lowercasing, so keyword patterns written with a
plain ``i`` match all of ``iptal``, ``İptal``, ``IPTAL``, and ``ıptal``.
Patterns must therefore also be written in folded form (``sandik`` rather than
``sandık``); the other Turkish letters (``ü ö ş ç ğ``) lowercase safely and
stay themselves.
"""

from __future__ import annotations

_COMBINING_DOT = "i̇"


def fold(text: str | None) -> str:
    """Fold mixed Turkish/English text for case-insensitive keyword matching."""
    if not text:
        return ""
    return (
        text.replace("İ", "i")
        .replace("I", "i")
        .lower()
        .replace("ı", "i")
        .replace(_COMBINING_DOT, "i")
    )
