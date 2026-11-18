#!/usr/bin/env python3
"""Fail if any text file in the project contains non-English script characters.

The project rule is simple: every UI string, comment, document and workflow is written
in English. This script scans the repository (skipping build output, binary assets and
the Gradle wrapper jar) and reports every character outside the Latin/ASCII range that
belongs to a non-Latin script.

Usage:
    python3 verify-english.py [path]

Exit code 0 means the tree is clean, 1 means at least one violation was found.
"""

from __future__ import annotations

import os
import sys

SKIP_DIRS = {
    ".git",
    ".gradle",
    "build",
    ".idea",
    ".kotlin",
    "gradle/wrapper",
}

TEXT_SUFFIXES = {
    ".kt",
    ".kts",
    ".java",
    ".xml",
    ".md",
    ".yml",
    ".yaml",
    ".json",
    ".properties",
    ".pro",
    ".txt",
    ".py",
    ".sh",
    ".html",
    ".css",
    ".js",
}

BINARY_SUFFIXES = {
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".apk",
    ".jar",
    ".aar",
    ".so",
    ".keystore",
    ".jks",
}

# Ranges for scripts that must never appear: Hangul, CJK ideographs, Kana, Cyrillic,
# Greek, Arabic, Hebrew, Devanagari, Thai and full-width forms.
FORBIDDEN_RANGES = (
    (0x1100, 0x11FF),   # Hangul Jamo
    (0x2E80, 0x2FDF),   # CJK radicals
    (0x3000, 0x303F),   # CJK symbols and punctuation
    (0x3040, 0x30FF),   # Hiragana / Katakana
    (0x3130, 0x318F),   # Hangul compatibility Jamo
    (0x3400, 0x4DBF),   # CJK extension A
    (0x4E00, 0x9FFF),   # CJK unified ideographs
    (0xA960, 0xA97F),   # Hangul Jamo extended A
    (0xAC00, 0xD7AF),   # Hangul syllables
    (0xD7B0, 0xD7FF),   # Hangul Jamo extended B
    (0xF900, 0xFAFF),   # CJK compatibility ideographs
    (0xFF00, 0xFFEF),   # Half-width and full-width forms
    (0x0400, 0x04FF),   # Cyrillic
    (0x0370, 0x03FF),   # Greek
    (0x0600, 0x06FF),   # Arabic
    (0x0590, 0x05FF),   # Hebrew
    (0x0900, 0x097F),   # Devanagari
    (0x0E00, 0x0E7F),   # Thai
)

# Characters that are allowed even though they sit outside ASCII: the BIP-39 style
# typographic characters and currency marks the wallet needs in its UI.
ALLOWED_CHARACTERS = {
    "\u00b7",  # middle dot
    "\u2018", "\u2019",  # curly single quotes
    "\u201c", "\u201d",  # curly double quotes
    "\u2013", "\u2014",  # en dash, em dash
    "\u2026",  # ellipsis
    "\u20a9",  # won sign
    "\u20bf",  # bitcoin sign
    "\u20ac",  # euro sign
    "\u26a1",  # high voltage (lightning)
    "\u2192",  # right arrow
    "\u2713",  # check mark
    "\u00a0",  # non-breaking space
}


def is_forbidden(character: str) -> bool:
    if character in ALLOWED_CHARACTERS:
        return False
    code = ord(character)
    for start, end in FORBIDDEN_RANGES:
        if start <= code <= end:
            return True
    return False


def iter_files(root: str):
    for current_root, dirs, files in os.walk(root):
        dirs[:] = [
            d for d in dirs
            if d not in SKIP_DIRS and not os.path.join(current_root, d).endswith("build")
        ]
        for name in files:
            path = os.path.join(current_root, name)
            suffix = os.path.splitext(name)[1].lower()
            if suffix in BINARY_SUFFIXES:
                continue
            if suffix not in TEXT_SUFFIXES:
                continue
            yield path


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    violations = []

    for path in sorted(iter_files(root)):
        try:
            with open(path, "r", encoding="utf-8") as handle:
                lines = handle.readlines()
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(lines, start=1):
            bad = [ch for ch in line if is_forbidden(ch)]
            if bad:
                sample = "".join(sorted(set(bad)))
                violations.append(f"{os.path.relpath(path, root)}:{number}: {sample}")

    if violations:
        print("Non-English characters found:")
        for violation in violations:
            print("  " + violation)
        return 1

    print("OK: no non-English script characters found.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
