#!/usr/bin/env python3
"""Compare the same Minecraft advancements, using both parties' recorded game timers."""
import argparse
import json
from pathlib import Path

def compare(record,reference):
    actual=json.loads(record.read_text(encoding="utf-8"))["advancements"]
    expected=json.loads(reference.read_text(encoding="utf-8"))["timelines_ms"]
    rows=[]
    for key,goal in expected.items():
        category,criterion=key.split(".",1)
        own=actual.get("minecraft:"+category+"/"+criterion,{})
        if own.get("complete") and isinstance(own.get("igt"),int):
            rows.append({"event":key,"astra_igt_ms":own["igt"],"xiamoys_igt_ms":goal,"difference_ms":own["igt"]-goal})
    return rows

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("record",type=Path,help="speedrunigt/record.json in the closed local world")
    opts=parser.parse_args()
    print(json.dumps(compare(opts.record,Path(__file__).resolve().parents[1]/"champion.json"),indent=2))
