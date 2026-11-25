#!/usr/bin/env python3
"""Download public MCSR Weekly Race metadata for an agent's reference."""
import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE_URL = "https://api.mcsrranked.com/weekly-race"


def summarize(document):
    if document.get("status") != "success":
        raise ValueError("周赛接口未返回成功状态")
    race = document["data"]
    seeds = race["seed"]
    standings = sorted(race["leaderboard"], key=lambda row: row["rank"])
    return {
        "race_id": race["id"], "ends_at": race["endsAt"],
        "seeds": {k: str(seeds[k]) if seeds[k] is not None else None for k in ("overworld", "nether", "theEnd", "rng")},
        "flags": seeds.get("flags"),
        "reference_runs": [{"rank": e["rank"], "player": e["player"]["nickname"],
                            "time_ms": e["time"], "replay_exists": e["replayExist"]}
                           for e in standings],
        "purpose": "参考数据。周赛世界、交易规则和可执行回放须另行验证。",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("week", nargs="?", type=int, help="省略时获取当前周")
    parser.add_argument("--from-file", type=Path, help="从已保存的原始 API JSON 离线导入")
    args = parser.parse_args()
    if args.week is not None and args.week < 1:
        parser.error("周数必须大于零")
    url = BASE_URL if args.week is None else f"{BASE_URL}/{args.week}"
    try:
        if args.from_file:
            body = args.from_file.read_bytes()
        else:
            request = urllib.request.Request(url, headers={"User-Agent": "AI-Speedrun-Arena/0.1"})
            with urllib.request.urlopen(request, timeout=20) as resp:
                body = resp.read(4_000_001)
        if len(body) > 4_000_000:
            raise ValueError("周赛响应超出大小限制")
        print(json.dumps(summarize(json.loads(body)), ensure_ascii=False, indent=2))
    except (OSError, KeyError, TypeError, ValueError) as error:
        sys.exit(f"读取周赛参考资料失败：{error}")


if __name__ == "__main__":
    main()
