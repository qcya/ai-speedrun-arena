#!/usr/bin/env python3
"""Run repeated, reset-separated attempts on the built-in singleplayer practice course."""
import argparse
import json
import os
from pathlib import Path
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from arena.core import Arena
from arena.model import ArenaError, read_json, validate_plan


def reset_course(arena, state, variant, wait=20):
    before = state.get("course_generation")
    if type(before) is not int or before < 1 or state.get("course_variant") != variant:
        raise ArenaError("请进入自带练习地图，并确认与 --villagers 对应的村民设置。")
    name = uuid.uuid4().hex
    pending = arena.bridge / "course-reset.json"
    temporary = arena.bridge / f".reset-{name}.tmp"
    # Never replace another operator's pending request.
    packet = {"id": name, "session_id": state["session_id"],
              "expires_at_ms": int((time.time() + wait) * 1000)}
    temporary.write_text(json.dumps(packet), encoding="utf-8")
    try:
        os.link(temporary, pending)
    except FileExistsError:
        raise ArenaError(f"已有训练场重置请求待处理：{pending}") from None
    finally:
        temporary.unlink(missing_ok=True)
    until = time.monotonic() + wait
    receipt = arena.bridge / "resets" / (name + ".json")
    while time.monotonic() < until:
        if receipt.is_file():
            ack = read_json(receipt)
            if ack.get("id") != name or ack.get("status") != "sent":
                raise ArenaError(f"游戏拒绝重置：{ack.get('reason')}")
            now = arena.state()
            if state["session_id"] != now.get("session_id"):
                raise ArenaError("世界已切换，本轮未计时")
            pos = now.get("player", {})
            at_spawn = all(abs(pos.get(axis, -9999) - expected) < .02 for axis, expected in
                           (("x", 0.5), ("y", 65.0), ("z", 0.5), ("yaw", 0.0), ("pitch", 22.0)))
            # Rebuilding a previous bed can drop an item via neighbor updates.
            clean = not pos.get("inventory") and not any(
                entity.get("entity") == "minecraft:item" for entity in now.get("nearby_entities", []))
            entities = [entity.get("entity") for entity in now.get("nearby_entities", [])]
            layout = ("minecraft:villager" in entities if variant else
                      "minecraft:villager" not in entities and now.get("target", {}).get("block") == "minecraft:red_bed")
            if (layout and clean and now["ready"] and now.get("course_generation") == before + 1 and
                    now.get("course_variant") == variant and at_spawn):
                return now
        time.sleep(0.08)
    raise ArenaError(f"重置未在 {wait} 秒内确认，未开赛。请检查游戏焦点与数据包，场次 {name}")


def contest(arena, plans, rounds, variant):
    if not plans:
        raise ArenaError("至少需要一个参赛方案")
    for plan in plans:
        validate_plan(plan)
    if any(plan["goal"] != plans[0]["goal"] for plan in plans):
        raise ArenaError("同一场比赛的所有方案必须使用相同目标")
    if len({plan["agent"] for plan in plans}) != len(plans):
        raise ArenaError("每个参赛方案需要独立的 agent 名称")
    if not 1 <= rounds <= 10000:
        raise ArenaError("--rounds 必须在 1 到 10000 之间")
    results = []
    label = "course-v1-villager" if variant else "course-v1-clear"
    for round_index in range(rounds):
        offset = round_index % len(plans)
        for plan in plans[offset:] + plans[:offset]:
            result = arena.run(plan, snapshot=label, prepare=lambda a, s: reset_course(a, s, variant))
            results.append({k: result[k] for k in ("id", "agent", "ticks", "status", "reason")})
            print(f"[{round_index + 1}/{rounds}] {result['agent']}  {result['status']}  "
                  f"{result['ticks']} tick  {result['reason']}", file=sys.stderr, flush=True)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plans", type=Path, nargs="+")
    parser.add_argument("--game-dir", type=Path)
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--rounds", default=1, type=int)
    parser.add_argument("--villagers", action="store_true", help="要求训练场已开启村民变量")
    args = parser.parse_args()
    try:
        results = contest(Arena(args.data_dir, args.game_dir), [read_json(p) for p in args.plans],
                          args.rounds, int(args.villagers))
        print(json.dumps(results, indent=2, ensure_ascii=False))
    except (OSError, ArenaError) as e:
        parser.exit(1, f"{e}\n")


if __name__ == "__main__":
    main()
