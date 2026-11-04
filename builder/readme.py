"""Writes the supported-systems tables in README.md and README-public.txt from systems.json.

Each README marks the generated part with a start and an end line; everything between
them is replaced. The tables describe the public (released) build, so a system's
'publicCore' is shown when it has one.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SYSTEMS_FILE = Path(__file__).resolve().parent / "systems.json"

MD_FILE = ROOT / "README.md"
MD_START = "<!-- systems-table:start (generated from builder/systems.json by ./raineywrapper readme) -->"
MD_END = "<!-- systems-table:end -->"

TXT_FILE = ROOT / "README-public.txt"
TXT_START = "<<systems-list:start>>"
TXT_END = "<<systems-list:end>>"


class ReadmeError(Exception):
    pass


def _load() -> tuple[dict[str, str], list[dict]]:
    data = json.loads(SYSTEMS_FILE.read_text())
    return data.get("coreNames", {}), list(data["systems"].values())


def _extensions(system: dict) -> str:
    # '*.xiso.iso' files are matched as extension 'xiso' (see systems.json), so show the real suffix.
    return " ".join(".xiso.iso" if e == "xiso" else f".{e}" for e in system["extensions"])


def _core_name(core_names: dict[str, str], system: dict) -> str:
    core = system.get("publicCore") or system["core"]
    if core not in core_names:
        raise ReadmeError(f"systems.json: no display name for core '{core}' in 'coreNames'")
    return core_names[core]


def _system_name(system: dict) -> str:
    return system["name"] + (" (needs your own BIOS)" if system.get("biosRequired") else "")


def _external_names(system: dict, markdown: bool) -> str:
    names = []
    for e in system["external"]:
        if e["name"] in (n for n, _ in names):
            continue
        names.append((e["name"], e.get("url")))
    if markdown:
        return " or ".join(f"[{n}]({u})" if u else n for n, u in names)
    return " or ".join(n for n, _ in names)


def markdown_table() -> str:
    core_names, systems = _load()
    rows = ["| System | Emulator inside each game app | Files |", "|---|---|---|"]
    for s in systems:
        if s.get("external"):
            emulator = f"opens in {_external_names(s, True)}, installed separately"
        else:
            emulator = _core_name(core_names, s)
        rows.append(f"| {_system_name(s)} | {emulator} | `{_extensions(s)}` |")
    return "\n".join(rows)


def text_list() -> str:
    core_names, systems = _load()
    built_in = [s for s in systems if not s.get("external")]
    external = [s for s in systems if s.get("external")]

    def line(left: str, right: str, width: int) -> str:
        return f"  - {left.ljust(width)}  ({right})"

    for s in built_in:
        _core_name(core_names, s)  # same check as the Markdown table
    entries = [(_system_name(s), s) for s in built_in]
    entries += [(f"{_system_name(s)}: opens in {_external_names(s, False)}", s) for s in external]
    width = max(len(left) for left, _ in entries)

    lines = ["Built in (nothing else to install):"]
    lines += [line(left, _extensions(s), width) for left, s in entries[:len(built_in)]]
    if external:
        lines += ["", "Needs an emulator app installed (the game's icon opens it straight into the game):"]
        lines += [line(left, _extensions(s), width) for left, s in entries[len(built_in):]]
    return "\n".join(lines)


def _replace(text: str, start: str, end: str, body: str, path: Path) -> str:
    if text.count(start) != 1 or text.count(end) != 1 or text.index(start) > text.index(end):
        raise ReadmeError(f"{path.name}: expected one '{start}' line followed by one '{end}' line")
    head, rest = text.split(start, 1)
    _, tail = rest.split(end, 1)
    return f"{head}{start}\n{body}\n{end}{tail}"


def _targets() -> list[tuple[Path, str]]:
    return [
        (MD_FILE, _replace(MD_FILE.read_text(), MD_START, MD_END, markdown_table(), MD_FILE)),
        (TXT_FILE, _replace(TXT_FILE.read_text(), TXT_START, TXT_END, text_list(), TXT_FILE)),
    ]


def update() -> list[Path]:
    """Rewrites the tables; returns the files that changed."""
    changed = []
    for path, new in _targets():
        if path.read_text() != new:
            path.write_text(new)
            changed.append(path)
    return changed


def stale() -> list[Path]:
    """Files whose tables don't match systems.json (nothing is written)."""
    return [path for path, new in _targets() if path.read_text() != new]


def strip_markers(text: str) -> str:
    """README-public.txt without the marker lines, for the release folder."""
    return "".join(l for l in text.splitlines(keepends=True) if l.strip() not in (TXT_START, TXT_END))
