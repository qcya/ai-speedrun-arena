#!/usr/bin/env python3
"""Compare the same Minecraft advancements, using both parties' recorded game timers."""
import argparse
import json
from pathlib import Path

# The challenge is bound to this published Week 113 run even if the leaderboard changes.
XIAMOYS_IGT_MS = 335_528


def beats_xiamoys(igt_ms):
    return type(igt_ms) is int and 0 < igt_ms < XIAMOYS_IGT_MS


def compare(record,reference):
    actual=json.loads(record.read_text(encoding="utf-8"))["advancements"]
    baseline=json.loads(reference.read_text(encoding="utf-8"))
    player=baseline["player"]
    expected=baseline["timelines_ms"]
    rows=[]
    for key,goal in expected.items():
        category,criterion=key.split(".",1)
        own=actual.get("minecraft:"+category+"/"+criterion,{})
        if own.get("complete") and isinstance(own.get("igt"),int):
            row={"event":key,"astra_igt_ms":own["igt"],"reference_player":player,
                 "reference_igt_ms":goal,"difference_ms":own["igt"]-goal}
            if player == "xiamoys":
                row["xiamoys_igt_ms"]=goal
            rows.append(row)
    return rows

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("record",type=Path,help="speedrunigt/record.json in the closed local world")
    parser.add_argument("--reference", choices=("xiamoys","Ormo"), default="xiamoys")
    opts=parser.parse_args()
    filename="champion.json" if opts.reference == "xiamoys" else "ormo-reference.json"
    print(json.dumps(compare(opts.record,Path(__file__).resolve().parents[1]/filename),indent=2))
