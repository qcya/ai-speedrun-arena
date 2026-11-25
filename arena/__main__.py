"""Run with python3 -m arena. Machine-readable output goes to stdout."""
import argparse
import json
import sqlite3
import sys

from .core import Arena, ArenaError
from .model import read_json, validate_plan


def _arguments(argv):
    parser = argparse.ArgumentParser(prog="python3 -m arena", description="Minecraft 1.16.1 本地单人训练竞技场")
    parser.add_argument("--game-dir", help="包含 mods 和 saves 的游戏实例目录")
    parser.add_argument("--data-dir", help="存放 runs.sqlite3 的数据目录")
    sub = parser.add_subparsers(dest="command", required=True)
    for name, help_text in [("doctor", "检查本地路径、心跳和未结算场次"), ("state", "查看游戏当前状态"),
                            ("demo", "生成两个离线合成演示场次"), ("list", "列出所有场次")]:
        sub.add_parser(name, help=help_text)
    p = sub.add_parser("validate", help="验证动作方案")
    p.add_argument("plan")
    p = sub.add_parser("show", help="读取场次、原始方案与轨迹")
    p.add_argument("id")
    for name, help_text in [("run", "执行一个方案"), ("batch", "依次执行多个方案，每场之间手动恢复存档")]:
        p = sub.add_parser(name, help=help_text)
        p.add_argument("plans", nargs=("+" if name == "batch" else 1), metavar="PLAN")
        p.add_argument("--ready-timeout", type=float, default=45, help="等待已聚焦的单人生存世界，默认 45 秒")
        p.add_argument("--timeout", type=float, help="提交后等待结果的秒数，默认按方案时长推算")
        p.add_argument("--snapshot", help="声明本轮恢复的原始存档标识；建议使用备份哈希")
    p = sub.add_parser("recover", help="恢复一场被打断的记录，导入已落地的游戏结果")
    p.add_argument("--discard", action="store_true", help="已关闭游戏时，将缺少有效结果的场次标为中止")
    p = sub.add_parser("serve", help="启动本地观察台")
    p.add_argument("--host", default="127.0.0.1", choices=("127.0.0.1", "localhost", "::1"),
                   help="本地回环地址")
    p.add_argument("--port", type=int, default=8765)
    return parser.parse_args(argv)


def _print(data):
    json.dump(data, sys.stdout, ensure_ascii=False, allow_nan=False, indent=2)
    print()


def main(argv=None):
    args = _arguments(argv)
    try:
        if args.command == "validate":
            plan = validate_plan(read_json(args.plan))
            _print({"valid": True, "agent": plan["agent"], "steps": len(plan["steps"]),
                    "max_ticks": sum(step["ticks"] for step in plan["steps"]), "goal": plan["goal"]})
            return 0
        arena = Arena(args.data_dir, args.game_dir)
        if args.command == "show":
            output = arena.get_run(args.id)
        elif args.command == "list":
            output = arena.list_runs()
        elif args.command == "demo":
            output = arena.demo()
            output = [{k: v for k, v in row.items() if k not in ("trace", "plan", "result")} for row in output]
        elif args.command == "doctor":
            output = arena.doctor()
        elif args.command == "state":
            output = arena.state()
        elif args.command == "recover":
            output = arena.recover(discard=args.discard)
        elif args.command == "serve":
            from .web import serve
            if not 0 <= args.port <= 65535:
                raise ArenaError("端口必须介于 0 和 65535")
            serve(arena, args.host, args.port)
            return 0
        else:
            plans = [validate_plan(read_json(path)) for path in args.plans]
            if len(plans) > 1 and not sys.stdin.isatty():
                raise ArenaError("batch 需要交互终端，以便每场之间确认手动恢复同一个存档；"
                                 "也可恢复存档后分别使用 run")
            records = []
            previous = None
            for index, plan in enumerate(plans):
                if index:
                    print("请退出世界，将同一原始备份恢复为新的可写副本。打开副本后回到此终端，"
                          "按 Enter 继续，或 Ctrl+C 停止。", file=sys.stderr, flush=True)
                    if sys.stdin.readline() == "":
                        raise ArenaError("输入已结束；已完成的场次已保存")
                print(f"等待 {plan['agent']}：请切回游戏、关闭菜单并保持窗口焦点。",
                      file=sys.stderr, flush=True)
                row = arena.run(plan, ready_timeout=args.ready_timeout, timeout=args.timeout,
                                snapshot=args.snapshot, previous_session=previous)
                previous = row["session_id"]
                records.append(row)
                print(f"{row['agent']} · {row['status']} · {row['reason']}", file=sys.stderr, flush=True)
            output = records if args.command == "batch" else records[0]
            _print(output)
            return 0 if all(r["status"] == "success" for r in records) else 1
        _print(output)
        return 0
    except (ArenaError, OSError, sqlite3.Error, KeyError, KeyboardInterrupt) as exc:
        msg = f"找不到场次 {exc}" if isinstance(exc, KeyError) else str(exc)
        print(f"竞技场：{msg or '已中断'}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
