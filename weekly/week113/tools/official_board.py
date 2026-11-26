"""Read the active weekly race and bind a release benchmark to the original map."""
from datetime import datetime, timezone
import json
from urllib.request import Request, urlopen

ENDPOINT = "https://api.mcsrranked.com/weekly-race"


def current_benchmark(course):
    req = Request(ENDPOINT, headers={"User-Agent": "Astra local Week 113 film", "Cache-Control": "no-cache"})
    with urlopen(req, timeout=25) as response:
        live = json.load(response)["data"]
    if live["id"] != course["week"] or live["seed"] != course["seed"]:
        raise ValueError("The active weekly race has changed; this recording cannot claim the current benchmark")
    leaders = sorted((r for r in live["leaderboard"] if 1 <= r["rank"] <= 5), key=lambda r: r["rank"])
    if not leaders or leaders[0]["rank"] != 1 or leaders[0]["time"] <= 0:
        raise ValueError("The active leaderboard has no usable first-place time")
    snapshot = dict(course)
    snapshot["qualifying_benchmark_utc"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    snapshot["top_five"] = [{"rank": r["rank"], "player": r["player"]["nickname"],
                             "time_ms": r["time"], "has_replay": r["replayExist"]} for r in leaders]
    return snapshot
