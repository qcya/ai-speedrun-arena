"""Version 1 plans, evidence validation and local comparison groups."""
import hashlib
import json
import math
import re
from pathlib import Path


class ArenaError(Exception):
    """A user-correctable arena error."""


MAX_STEPS = 100_000
MAX_TICKS = 1_200_000
MAX_PACKET = 16 * 1024 * 1024
MAX_RESULT = 192 * 1024 * 1024
KEYS = {"forward", "back", "left", "right", "jump", "sprint", "sneak", "attack", "use"}


def require(ok, message):
    if not ok:
        raise ArenaError(message)


def _unique_pairs(pairs):
    obj = {}
    for key, value in pairs:
        require(key not in obj, f"重复的 JSON 字段 {key}")
        obj[key] = value
    return obj


def read_json(path, limit=MAX_PACKET):
    path = Path(path)
    try:
        with path.open("rb") as stream:
            data = stream.read(limit + 1)
        require(len(data) <= limit, f"文件超过大小上限 {path}")
        return json.loads(data.decode("utf-8-sig"), object_pairs_hook=_unique_pairs,
                          parse_constant=_invalid_constant, parse_float=_finite_float)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise ArenaError(f"JSON 无法读取 {path} ({exc})") from exc


def _finite_float(text):
    value = float(text)
    require(math.isfinite(value), "JSON 数字溢出有限范围")
    return value


def _invalid_constant(value):
    raise ArenaError(f"JSON 数字必须有限 {value}")


def encode(value):
    return json.dumps(value, ensure_ascii=True, allow_nan=False, sort_keys=True,
                      separators=(",", ":")).encode("ascii")


def _num(value):
    return (type(value) in (int, float) and -10**308 <= value <= 10**308
            and math.isfinite(value))


def _positive_int(value):
    return type(value) is int and value > 0


def _text(value, limit):
    return (isinstance(value, str) and 0 < len(value.strip()) <= limit
            and len(value) <= limit and all(ord(ch) >= 32 and not 0xd800 <= ord(ch) <= 0xdfff
                                            for ch in value))


def _fields(data, required, optional, label):
    require(isinstance(data, dict), f"{label} 需要 JSON 对象")
    missing = required - data.keys()
    extra = data.keys() - required - optional
    require(not missing and not extra,
            f"{label} 字段不匹配 (缺少 {sorted(missing)}，未知 {sorted(extra)})")


def validate_plan(plan):
    _fields(plan, {"version", "agent", "steps", "goal"}, set(), "方案")
    require(type(plan["version"]) is int and plan["version"] == 1, "仅支持方案 version=1")
    require(_text(plan["agent"], 128), "agent 需要 1–128 个可见字符")
    goal = plan["goal"]
    require(isinstance(goal, dict), "goal 需要 JSON 对象")
    kind = goal.get("type")
    if kind == "displacement":
        _fields(goal, {"type", "blocks"}, set(), "goal")
        require(_num(goal["blocks"]) and goal["blocks"] > 0, "位移 blocks 需要大于零的有限数")
    elif kind == "item":
        _fields(goal, {"type", "item", "count"}, set(), "goal")
        require(isinstance(goal["item"], str) and len(goal["item"]) <= 256 and
                re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", goal["item"]),
                "物品 ID 需要命名空间，例如 minecraft:red_bed")
        require(_positive_int(goal["count"]) and goal["count"] <= 2147483647, "count 需要正整数")
    elif kind == "dragon_exit":
        _fields(goal, {"type"}, set(), "goal")
    else:
        raise ArenaError(f"未知 goal.type {kind!r}")
    steps = plan["steps"]
    require(isinstance(steps, list) and 0 < len(steps) <= MAX_STEPS,
            f"steps 需要 1–{MAX_STEPS} 个动作")
    total = 0
    for index, step in enumerate(steps, 1):
        label = f"第 {index} 个动作"
        _fields(step, {"ticks", "keys"}, {"yaw", "pitch", "slot", "require_target", "label", "ui", "click"}, label)
        require(_positive_int(step["ticks"]), f"{label} ticks 需要正整数")
        total += step["ticks"]
        require(total <= MAX_TICKS, f"方案最多 {MAX_TICKS} ticks")
        keys = step["keys"]
        require(isinstance(keys, list) and all(isinstance(k, str) and k in KEYS for k in keys),
                f"{label} keys 包含未知按键")
        require(len(set(keys)) == len(keys), f"{label} keys 中有重复按键")
        if "ui" in step or "click" in step:
            require(("ui" in step) != ("click" in step)
                    and not keys and not (step.keys() & {"slot", "pitch", "yaw", "require_target"}),
                    f"{label} 界面操作需要 keys=[]，与视角、快捷栏和目标准星操作互斥")
            if "ui" in step:
                require(step["ui"] in ("inventory", "close"), f"{label} 未知界面操作")
            else:
                click = step["click"]
                _fields(click, {"slot", "button", "action"}, set(), f"{label}.click")
                require(type(click["slot"]) is int and 0 <= click["slot"] <= 32767,
                        f"{label} click.slot 范围 0–32767")
                require(type(click["button"]) is int and click["button"] in (0, 1),
                        f"{label} click.button 需要 0 或 1")
                require(click["action"] in ("pickup", "quick_move"), f"{label} click.action 无效")
        if "slot" in step:
            require(type(step["slot"]) is int and 0 <= step["slot"] <= 8, f"{label} slot 范围 0–8")
        if "yaw" in step:
            require(_num(step["yaw"]) and abs(step["yaw"]) <= 3.4e38, f"{label} yaw 需要有限角度")
        if "pitch" in step:
            require(_num(step["pitch"]) and -90 <= step["pitch"] <= 90, f"{label} pitch 范围 -90–90")
        if "require_target" in step:
            require(step["require_target"] in ("block", "entity", "miss"), f"{label} require_target 无效")
        if "label" in step:
            require(_text(step["label"], 256), f"{label} label 需要 1–256 个可见字符")
    return plan


def validate_result(result, run_id, plan):
    require(isinstance(result, dict) and result.get("id") == run_id, "结果 ID 与待处理场次不符")
    require(result.get("status") in ("success", "failed", "aborted"), "结果状态无效")
    budget = sum(s["ticks"] for s in plan["steps"])
    ticks = result.get("ticks")
    loading = result.get("loading_ticks", 0)
    require(type(ticks) is int and type(loading) is int and 0 <= loading <= ticks
            and ticks - loading <= budget, "结果 ticks 超出动作或维度加载范围")
    require(isinstance(result.get("reason"), str), "结果缺少 reason")
    points = result.get("trace")
    require(isinstance(points, list) and len(points) <= ticks + 1, "结果轨迹无效")
    previous = -1
    for point in points:
        require(isinstance(point, dict) and type(point.get("tick")) is int
                and previous <= point["tick"] <= ticks and point["tick"] >= 0
                and all(_num(point.get(axis)) for axis in ("x", "y", "z"))
                and _text(point.get("dimension"), 256), "结果的轨迹点无效")
        previous = point["tick"]
    return result


def comparison(source, goal, snapshot, result, guards=None):
    """Group operator-attested copies only when the observed start also matches."""
    if source == "game" and not snapshot:
        return None, "训练记录；未声明恢复的存档快照"
    start = result.get("start")
    seed = result.get("world_seed")
    stamp = result.get("start_world_time")
    axes = ("x", "y", "z", "yaw", "pitch", "health", "food")
    if not (result.get("game_version") == "1.16.1" and result.get("game_mode") == "survival"
            and isinstance(seed, str) and re.fullmatch(r"-?[0-9]{1,20}", seed)
            and type(stamp) is int and isinstance(start, dict)
            and all(_num(start.get(field)) for field in axes)
            and _text(start.get("dimension"), 256) and type(start.get("selected_slot")) is int
            and 0 <= start["selected_slot"] <= 8 and isinstance(start.get("inventory"), list)):
        return None, "训练记录；缺少可核对的起点、种子或模式"
    if source == "game":
        measured = ("saturation", "experience_progress", "velocity_x", "velocity_y", "velocity_z")
        flags = ("on_ground", "sprinting", "sneaking")
        if not (all(_num(start.get(k)) for k in measured) and all(type(start.get(k)) is bool for k in flags)
                and type(start.get("experience_level")) is int and isinstance(start.get("effects"), list)):
            return None, "训练记录；起点缺少移动状态、经验或药水效果"
    if goal["type"] == "displacement":
        goal = {**goal, "blocks": float(goal["blocks"])}
    basis = {"source": source, "rules": "speedrun-arena-v1", "version": result["game_version"],
             "mode": result["game_mode"], "seed": seed, "snapshot": snapshot, "goal": goal,
             "start": start, "start_world_time": stamp}
    course_variants = {"course-v1-clear": 0, "course-v1-villager": 1}
    note = ("离线合成演示；相同夹具与目标" if source == "demo"
            else "操作者声明已恢复同一快照；已按实际起点、种子、模式、时间与目标分组")
    if source == "game" and snapshot.startswith("course-v1-"):
        variant = start.get("course_variant")
        generation = start.get("course_generation")
        supported_goal = goal["type"] == "displacement" or (
            goal["type"] == "item" and goal["item"] == "minecraft:red_bed")
        if not (guards and guards.get("expected_course_generation") == generation
                and guards.get("expected_course_variant") == variant
                and start.get("course_id") == "asa:course_v1" and _positive_int(generation)
                and type(variant) is int and snapshot in course_variants
                and variant == course_variants[snapshot] and supported_goal):
            return None, "训练记录；练习场重置标记、布局或目标不匹配"
        # The contained course rebuilds its blocks and clears the inventory before
        # each attempt. Absolute world time and the reset sequence number advance.
        basis["start"] = {k: v for k, v in start.items() if k != "course_generation"}
        del basis["start_world_time"]
        note = "内置练习场；已按重置标记、布局、实际起点、种子及目标分组"
    return hashlib.sha256(encode(basis)).hexdigest(), note
