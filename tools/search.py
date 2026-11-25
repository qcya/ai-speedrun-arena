#!/usr/bin/env python3
"""Evaluate one route parameter in the real training world and export the fastest successful plan."""
import argparse
import copy
import json
import math
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from arena.core import Arena
from arena.model import ArenaError, read_json, validate_plan
from tools.tournament import reset_course


def candidates(base, step, field, values):
    validate_plan(base)
    if not 1 <= step <= len(base["steps"]):
        raise ArenaError("步序超出范围；第一步为 1")
    if field not in ("ticks", "pitch", "yaw") or not values or len(values) > 128:
        raise ArenaError("只支持 ticks/pitch/yaw，每次接受 1–128 个候选值")
    variants = []
    for raw in values:
        try:
            value = int(raw) if field == "ticks" else float(raw)
        except ValueError as exc:
            raise ArenaError(f"候选值不合法：{raw}") from exc
        if not math.isfinite(value):
            raise ArenaError("需要有限数值")
        plan = copy.deepcopy(base)
        plan["steps"][step - 1][field] = value
        variants.append((value, validate_plan(plan)))
    if len({v for v, _ in variants}) != len(variants):
        raise ArenaError("候选值重复")
    return variants


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("plan", type=Path)
    p.add_argument("--step", type=int, required=True)
    p.add_argument("--field", choices=["ticks", "pitch", "yaw"], required=True)
    p.add_argument("--values", nargs="+", required=True)
    p.add_argument("--rounds", type=int, default=1)
    p.add_argument("--out", type=Path, required=True, help="保存最快成功场次的方案，必须是新文件")
    p.add_argument("--villagers", action="store_true")
    p.add_argument("--data-dir", type=Path)
    p.add_argument("--game-dir", type=Path)
    a = p.parse_args()
    try:
        if not 1 <= a.rounds <= 10000 or a.out.exists():
            raise ArenaError("--rounds 取 1 到 10000，且 --out 必须指向新文件")
        variants = candidates(read_json(a.plan), a.step, a.field, a.values)
        arena, variant = Arena(a.data_dir, a.game_dir), int(a.villagers)
        attempts, best = [], None
        for round_index in range(a.rounds):
            offset = round_index % len(variants)
            for value, plan in variants[offset:] + variants[:offset]:
                row = arena.run(plan, snapshot="course-v1-villager" if variant else "course-v1-clear",
                                prepare=lambda host, state: reset_course(host, state, variant))
                item = {k: row[k] for k in ("id", "status", "ticks", "reason", "comparison_key")}
                item["value"] = value
                attempts.append(item)
                print(f"{a.field}={value}: {row['status']} / {row['ticks']} tick", file=sys.stderr, flush=True)
                if row["status"] == "success" and (best is None or row["ticks"] < best[0]["ticks"]):
                    best = (item, plan)
        if best:
            with a.out.open("x", encoding="utf-8") as dest:
                json.dump(best[1], dest, indent=2, ensure_ascii=False)
                dest.write("\n")
        print(json.dumps({"step": a.step, "field": a.field, "attempts": attempts,
                          "best_observed": best[0] if best else None}, indent=2, ensure_ascii=False))
        return 0 if best else 1
    except (ArenaError, OSError, ValueError) as exc:
        p.exit(2, f"{exc}\n")


if __name__ == "__main__":
    sys.exit(main())
