#!/usr/bin/env python3
"""Blackwater's buildings (D-117); none yet."""
from __future__ import annotations

from citykit import Building

CITY = "blackwater"


def buildings() -> list[Building]:
    return []


def zones() -> list[tuple[float, list[tuple[str, int]]]]:
    return []


def citystyle(all_buildings: list[Building]) -> dict | None:
    return None
