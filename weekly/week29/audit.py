#!/usr/bin/env python3
"""Audit the original local Week 29 observer's JSONL records, including gzip archives."""

import argparse
import gzip
import json
import math
import re
import zlib
from pathlib import Path

OVERWORLD = "minecraft:overworld"
END = "minecraft:the_end"
# Actual 1.16.1 default spawn from the generated Week 29 level.dat. Individual
# survival players start on a safe block inside the default horizontal radius.
WORLD_SPAWN_BLOCK = {"x": -240, "y": 69, "z": 181}
SPAWN_RADIUS = 10
DIMENSIONS = {OVERWORLD, END, "minecraft:the_nether"}
PICKAXES = {name + "_pickaxe" for name in ("wooden", "stone", "iron", "golden", "diamond", "netherite")}
BED_ITEMS = {color + "_bed" for color in (
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
)}
DYING_PHASE = re.compile(r"dying(?:\s*\(#\d+\))?", re.IGNORECASE)


def object_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate field {key!r}")
        result[key] = value
    return result


def integer(value, name):
    if type(value) is not int or value < 0:
        raise ValueError(f"{name} must be a nonnegative integer")
    return value


def number(value, name):
    if type(value) not in (float, int) or not math.isfinite(value):
        raise ValueError(f"{name} must be a finite number")
    return value


def state_values(row):
    dimension = row.get("dimension")
    if dimension not in DIMENSIONS:
        raise ValueError("unknown or missing vanilla dimension")
    position = {axis: number(row.get(axis), axis) for axis in ("x", "y", "z")}
    health = number(row.get("hp"), "hp")
    food = integer(row.get("food"), "food")
    if health < 0 or food > 20:
        raise ValueError("invalid player health or food")
    items = row.get("inv")
    if not isinstance(items, dict):
        raise ValueError("inv must be an object")
    for item, count in items.items():
        if not item or integer(count, f"inv.{item}") == 0:
            raise ValueError("inventory counts must be positive")
    dragon = row.get("dragon_hp")
    phase = row.get("dragon_phase")
    if "dragon_hp" in row or "dragon_phase" in row:
        if dimension != END or not 0 <= number(dragon, "dragon_hp") <= 200:
            raise ValueError("dragon samples require End dimension and health between 0 and 200")
        if not isinstance(phase, str) or not phase:
            raise ValueError("dragon sample missing its observed vanilla phase")
    return dimension, position, health, food, items, dragon, phase


def audit(path):
    """Return a JSON-serializable result. A structurally valid partial run is incomplete."""
    splits = {}
    visits = []
    deaths = []
    first_state = last_state = None
    spawn_time = last_time = last_tick = None
    credits = False
    state_count = max_beds = 0
    minimum_health = end_minimum_health = minimum_dragon = None
    previous_hp = None
    errors = []
    line = 0

    try:
        path = Path(path)
        opener = gzip.open if path.suffix.lower() == ".gz" else open
        with opener(path, "rt", encoding="utf-8") as stream:
            for line, raw in enumerate(stream, 1):
                row = json.loads(raw, object_pairs_hook=object_fields)
                if not isinstance(row, dict):
                    raise ValueError("record must be an object")
                event = row.get("event")
                tick = integer(row.get("tick"), "tick")
                time_ms = integer(row.get("time_ms"), "time_ms")
                if credits:
                    raise ValueError("records follow the terminal credits marker")
                if last_time is not None and (time_ms < last_time or tick < last_tick):
                    raise ValueError("tick or wall time moved backward")
                if event == "spawn":
                    if line != 1 or tick != 0:
                        raise ValueError("expected a single tick-zero spawn at the start")
                    spawn_time = time_ms
                    splits["spawn"] = {"tick": tick, "time_ms": time_ms, "elapsed_ms": 0}
                elif spawn_time is None:
                    raise ValueError("first record must be the tick-zero spawn")
                elif event == "state":
                    expected_tick = 1 + 10 * state_count
                    if tick != expected_tick:
                        raise ValueError(f"expected state tick {expected_tick}, got {tick}")
                    dimension, position, hp, food, inv, dragon, phase = state_values(row)
                    sample = dict(tick=tick, time_ms=time_ms, elapsed_ms=time_ms-spawn_time,
                                  dimension=dimension, position=position, player_hp=hp)
                    if state_count == 0:
                        if dimension != OVERWORLD or inv or hp != 20 or food != 20:
                            raise ValueError("first sample must show full health, full food and empty main inventory in the Overworld")
                        if any(abs(math.floor(position[k]) - WORLD_SPAWN_BLOCK[k]) > SPAWN_RADIUS for k in ("x", "z")):
                            raise ValueError("initial position is outside the Week 29 vanilla spawn area")
                        first_state = sample
                    state_count += 1
                    last_state = sample
                    if not visits or visits[-1]["dimension"] != dimension:
                        visits.append({"dimension": dimension, "first_sample": sample})
                    visits[-1]["last_sample"] = sample
                    minimum_health = hp if minimum_health is None else min(minimum_health, hp)
                    if hp == 0 and previous_hp != 0:
                        deaths.append(sample)
                    previous_hp = hp
                    beds = sum(inv.get(item, 0) for item in BED_ITEMS)
                    max_beds = max(max_beds, beds)
                    if dimension == OVERWORLD and "enter_end" not in splits:
                        picks = sorted(PICKAXES.intersection(inv))
                        if picks:
                            splits.setdefault("pickaxe", {**sample, "items": picks})
                        if beds:
                            splits.setdefault("beds", {**sample, "count": beds})
                    if dimension == END:
                        splits.setdefault("enter_end", sample)
                        end_minimum_health = hp if end_minimum_health is None else min(end_minimum_health, hp)
                        if dragon is not None:
                            minimum_dragon = dragon if minimum_dragon is None else min(minimum_dragon, dragon)
                            observation = {**sample, "dragon_hp": dragon, "dragon_phase": phase}
                            if dragon == 200:
                                splits.setdefault("full_health_dragon", observation)
                            full = splits.get("full_health_dragon")
                            if full and tick > full["tick"] and (dragon == 0 or DYING_PHASE.fullmatch(phase)):
                                splits.setdefault("dragon_zero_or_dying", observation)
                            if dragon == 0:
                                splits.setdefault("dragon_zero_hp", observation)
                elif event == "end_credits":
                    if last_state is None or not 0 <= tick - last_state["tick"] < 10:
                        raise ValueError("credits marker falls outside the final sampling interval")
                    credits = True
                    splits["end_credits"] = {"tick": tick, "time_ms": time_ms, "elapsed_ms": time_ms-spawn_time}
                else:
                    raise ValueError(f"unknown event {event!r}")
                last_tick, last_time = tick, time_ms
    except (OSError, EOFError, ValueError, TypeError, OverflowError, zlib.error) as exc:
        errors.append({"line": line, "message": str(exc)})

    required = ("spawn", "pickaxe", "beds", "enter_end", "full_health_dragon", "dragon_zero_or_dying", "end_credits")
    missing = [key for key in required if key not in splits]
    if first_state is None and "spawn" in splits:
        missing.insert(0, "initial_overworld_state")
    if credits and last_state["dimension"] != END:
        errors.append({"line": line, "message": "final state before credits was outside the End"})
    status = "invalid" if errors else "incomplete" if missing else "verified_local_completion"
    return {
        "status": status,
        "verified": status == "verified_local_completion",
        "state_count": state_count,
        "sampling_interval_ticks": 10,
        "local_elapsed_ms": splits["end_credits"]["elapsed_ms"] if status == "verified_local_completion" else None,
        "observed_span_ms": None if last_time is None else last_time-spawn_time,
        "splits": splits,
        "reference_world_spawn_block": WORLD_SPAWN_BLOCK,
        "first_state": first_state,
        "last_state": last_state,
        "dimension_visits": visits,
        "maximum_simultaneous_beds": max_beds,
        "minimum_observed_dragon_hp": minimum_dragon,
        "player_health": {"minimum": minimum_health, "minimum_in_end": end_minimum_health,
                          "final_observed": None if last_state is None else last_state["player_hp"]},
        "observed_player_death": bool(deaths),
        "first_zero_hp_samples_by_episode": deaths,
        "missing": missing,
        "errors": errors,
        "scope": "Local client observations only. The log records main inventory and sampled health. Seed, world generation settings, game mode, inputs and official race timing require separate evidence.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="observed-run.jsonl or .jsonl.gz")
    args = parser.parse_args()
    result = audit(args.log)
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
    return {"verified_local_completion": 0, "incomplete": 1, "invalid": 2}[result["status"]]


if __name__ == "__main__":
    raise SystemExit(main())
