#!/usr/bin/env python3
"""What the economy really did in a world, read from Core's telemetry (twelfth pass), set against the economy model.

Core writes one JSON line per economic event to ``underground_city_telemetry.jsonl`` in the world folder (``EconomyTelemetry``): sessions, orders accepted and delivered, service
calls, purchases at the exchanges, sales to shops and fences, encounters, a business's takings, arrests and checkpoint searches. Players are pseudonyms (a hash of the seed and the id),
never names. This script turns one or more of those files into a report a playtest can be judged by:

    * per player: active minutes, career and levels, credits earned by source (legal and underground orders, service calls, shops, fences, encounters, business), money spent on goods
      at the exchanges and lost to fines, the net, and net credits per active hour;
    * per order: how many active minutes passed between accepting and delivering it, against the model's hands-on minutes for it (the model assumes half of an active hour is
      hands-on work; the ratio here is what the playtest says instead);
    * travel: the active minutes between leaving one city and entering another;
    * police: arrests and searches, fines asked and paid, stacks taken.

It reads the world folder's file and its rolled ``.1``; nothing is uploaded or written anywhere but ``--out``. The report holds pseudonyms only.

    python scripts/economy_report.py <world folder or .jsonl> [...] [--out report.md]
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

TICKS_PER_MINUTE = 1200
FILE = "underground_city_telemetry.jsonl"
INCOME = {"order": None, "service": "service calls", "supply": "sold to shops", "fence": "fences", "encounter": "road encounters", "business": "business takings"}


def read(paths: list[Path]) -> list[dict]:
    events = []
    for path in paths:
        files = [path / (FILE + ".1"), path / FILE] if path.is_dir() else [path]
        for f in files:
            if not f.exists():
                continue
            for line in f.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError:
                    continue   # a line cut by a crash mid-write
    events.sort(key=lambda e: (e.get("t", 0), e.get("wall", 0)))
    return events


def sessions(events: list[dict]) -> dict[str, float]:
    """Active minutes each player was in the world: from a login (or the first event) to the logout (or the last event)."""
    start: dict[str, int] = {}
    last: dict[str, int] = {}
    total: dict[str, float] = defaultdict(float)
    for e in events:
        p, t = e["p"], e.get("t", 0)
        if e["kind"] == "login" or p not in start:
            if p in start and e["kind"] == "login":
                total[p] += (last[p] - start[p]) / TICKS_PER_MINUTE
            start[p] = t
        last[p] = t
        if e["kind"] == "logout":
            total[p] += (t - start.pop(p)) / TICKS_PER_MINUTE
    for p, t0 in start.items():
        total[p] += (last[p] - t0) / TICKS_PER_MINUTE
    return dict(total)


def summarise(events: list[dict]) -> dict:
    minutes = sessions(events)
    players: dict[str, dict] = {}
    orders: dict[str, list[float]] = defaultdict(list)
    travel: list[tuple[str, str, float]] = []
    left: dict[str, tuple[str, int]] = {}
    police = {"arrests": 0, "searches": 0, "asked": 0, "paid": 0, "stacks": 0}
    for e in events:
        p = e["p"]
        row = players.setdefault(p, {"career": e.get("career", ""), "level_first": e.get("level"), "level_last": e.get("level"), "income": defaultdict(float), "spent": defaultdict(float),
                                     "wallet_first": e.get("wallet"), "wallet_last": e.get("wallet"), "wallet_max": e.get("wallet", 0) or 0, "level_ups": 0})
        if e.get("career"):
            row["career"] = e["career"]
        if e.get("level") is not None:
            row["level_last"] = e["level"]
            if row["level_first"] is None:
                row["level_first"] = e["level"]
        if e.get("wallet") is not None:
            row["wallet_last"] = e["wallet"]
            row["wallet_max"] = max(row["wallet_max"], e["wallet"])
        kind = e["kind"]
        if kind == "order":
            row["income"]["underground orders" if e.get("legality") == "underground" else "legal orders"] += e.get("credits", 0)
            if e.get("minutes", -1) >= 0:
                orders[e["order"]].append(e["minutes"])
            if e.get("level_up"):
                row["level_ups"] += 1
        elif kind in INCOME:
            row["income"][INCOME[kind]] += e.get("credits", 0)
        elif kind == "purchase":
            row["spent"]["goods at the exchanges"] += e.get("credits", 0) - e.get("bank", 0)
        elif kind in ("arrest", "search"):
            row["spent"]["fines"] += e.get("fine", 0)
            police["arrests" if kind == "arrest" else "searches"] += 1
            police["asked"] += e.get("asked", e.get("fine", 0))
            police["paid"] += e.get("fine", 0)
            police["stacks"] += e.get("stacks", 0)
        elif kind == "city":
            if e.get("city", "") == "" and e.get("from") not in ("", "?", None):
                left[p] = (e["from"], e.get("t", 0))
            elif e.get("city") and p in left:
                origin, t0 = left.pop(p)
                if origin != e["city"]:
                    travel.append((origin, e["city"], (e.get("t", 0) - t0) / TICKS_PER_MINUTE))
    for p, row in players.items():
        row["minutes"] = minutes.get(p, 0.0)
        row["earned"] = sum(row["income"].values())
        row["lost"] = sum(row["spent"].values())
        row["net"] = row["earned"] - row["lost"]
        row["per_hour"] = 60 * row["net"] / row["minutes"] if row["minutes"] > 1 else None
    return {"players": players, "orders": dict(orders), "travel": travel, "police": police, "events": len(events)}


def model_minutes() -> dict[str, float]:
    """The economy model's hands-on minutes for each order (None when the model cannot be loaded, e.g. outside a checkout)."""
    try:
        import economy_model as em
        return {o.id: o.hands for o in em.Model().orders}
    except Exception:   # noqa: BLE001 - the report works without the model
        return {}


def table(rows: list[list], header: list[str]) -> str:
    out = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def report(s: dict, hands: dict[str, float]) -> str:
    lines = [f"# Economy telemetry report\n\n{s['events']} events. Players are pseudonyms. Arithmetic over what was recorded; a short session says little.\n"]
    rows = []
    for p, r in sorted(s["players"].items()):
        income = ", ".join(f"{k} {v:.0f}" for k, v in sorted(r["income"].items()) if v)
        spent = ", ".join(f"{k} {v:.0f}" for k, v in sorted(r["spent"].items()) if v)
        rows.append([p, r["career"], f"{r['level_first']}→{r['level_last']}", f"{r['minutes']:.0f}", income or "-", spent or "-", f"{r['net']:.0f}",
                     "-" if r["per_hour"] is None else f"{r['per_hour']:.0f}", f"{r['wallet_first']}→{r['wallet_last']} (max {r['wallet_max']})", r["level_ups"]])
    lines.append("## Players\n")
    lines.append(table(rows, ["player", "career", "level", "active min", "earned by source", "spent", "net", "net / active hour", "wallet", "level-ups"]))
    lines.append("\n## Orders: active minutes from accepting to delivering, against the model's hands-on minutes\n")
    rows = []
    shares = []
    for oid, ms in sorted(s["orders"].items()):
        med = statistics.median(ms)
        model = hands.get(oid)
        share = model / med if model and med > 0 else None
        if share:
            shares.append(share)
        rows.append([oid, len(ms), f"{med:.1f}", f"{min(ms):.1f}..{max(ms):.1f}", "-" if model is None else f"{model:.1f}", "-" if share is None else f"{share:.2f}"])
    lines.append(table(rows, ["order", "delivered", "median min", "range", "model hands-on min", "model ÷ observed"]) if rows else "No order was accepted and delivered in one run of the server.")
    if shares:
        lines.append(f"\nThe model takes half of an active hour as hands-on work (0.5). Here the model's minutes are {statistics.median(shares):.2f} of the observed ones (median over "
                     f"{len(shares)} order(s)): a value under 0.5 means orders took longer than the model assumes, over 0.5 shorter. Accept-to-deliver time includes travel and waiting.")
    lines.append("\n## Travel between cities (active minutes)\n")
    lines.append(table([[a, b, f"{m:.1f}"] for a, b, m in s["travel"]], ["from", "to", "minutes"]) if s["travel"] else "No trip from one city to another was recorded.")
    pol = s["police"]
    lines.append("\n## Police\n")
    lines.append(f"Arrests {pol['arrests']}, checkpoint searches that found something {pol['searches']}; fines asked {pol['asked']}, paid {pol['paid']}; stacks taken {pol['stacks']}.")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    events = read(args.paths)
    if not events:
        print("No telemetry found (the world folder holds underground_city_telemetry.jsonl once somebody has played in it).", file=sys.stderr)
        return 1
    text = report(summarise(events), model_minutes())
    if args.out:
        args.out.write_text(text, encoding="utf-8")
        print(f"wrote {args.out}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
