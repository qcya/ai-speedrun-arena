"""Read small native level.dat structures for a locally recorded attempt."""
import gzip
import io
import json
import math
import struct
import uuid


def read_level(path):
    with gzip.open(path, "rb") as f:
        buf = f.read(16 * 1024 * 1024 + 1)
    if len(buf)>16*1024*1024: raise ValueError("Unexpected level.dat size")
    f=io.BytesIO(buf)
    def read(fmt):
        return struct.unpack(">"+fmt,f.read(struct.calcsize(">"+fmt)))[0]
    def string():
        return f.read(read("H")).decode("utf-8",errors="replace")
    def payload(t,depth):
        if depth>48:raise ValueError("Invalid tag nesting")
        numeric={1:"b",2:"h",3:"i",4:"q",5:"f",6:"d"}
        if t in numeric:return read(numeric[t])
        if t==8:return string()
        if t==10:
            obj={}
            while True:
                tag=read("B")
                if tag==0:return obj
                name=string();obj[name]=payload(tag,depth+1)
        if t in (7,9,11,12):
            child=read("B") if t==9 else None
            n=read("i")
            if not 0<=n<=2_000_000:raise ValueError("Invalid tag length")
            if t==9:return [payload(child,depth+1) for _ in range(n)]
            stride={7:1,11:4,12:8}[t]
            raw=f.read(n*stride)
            if len(raw)!=n*stride:raise ValueError("Truncated NBT array")
            return raw
        raise ValueError(f"Unknown tag {t}")
    if read("B")!=10:raise ValueError("No NBT compound")
    string()
    return payload(10,0)["Data"]


def verify(world,course):
    data=read_level(world/"level.dat")
    w=data["WorldGenSettings"];s=course["seed"]
    for key,official in [("seed","overworld"),("nether_seed","nether"),("the_end_seed","theEnd"),("rng_seed","rng")]:
        if str(w[key])!=s[official]:raise ValueError("Native dimension or RNG seed mismatch")
    rules={"removeSpawnBlockEntities":"true","artificialLavaPool":"true","noMonsterInBastion":"true",
           "standardDragonPath":"true","lowerDragonFirstNodeHeight":"0","modifiedLootTables":"false",
           "instantDragonPerch":"true","rngSeedBaseVersion":"2","standardStronghold":"false"}
    if any(data["GameRules"].get(k)!=v for k,v in rules.items()):raise ValueError("Unexpected Week 113 race rules")
    if (data["Version"]["Name"],data["GameType"],data["Difficulty"],data["allowCommands"])!=("1.16.1",0,1,0):
        raise ValueError("Expected 1.16.1 Easy survival with commands off")
    if not (world/"data"/"rng.dat").is_file():raise ValueError("Missing native Ranked RNG save")
    return {"seeds":{k:v for k,v in s.items() if k!="flags"},"flags":207,"native_game_rules":rules,
            "version":"1.16.1","game_type":0,"difficulty":1,"commands":False,
            "seen_credits":bool(data.get("Player",{}).get("seenCredits",0))}


def stasis_shortcuts(rows):
    """Describe a verified high-ledge pearl recall when this route used one."""
    changes = [r for r in rows if r.get("event") == "render_distance_shortcut" and r.get("dim") == "nether"]
    low = next((r for r in changes if r.get("before") == 3 and r.get("after") == 2), None)
    if low is None:
        return None
    rods = next((r for r in rows if r.get("dim") == "nether" and r["elapsed_ms"] >= low["elapsed_ms"]
                 and sum(stack[2] for stack in r.get("inventory", []) if stack[1] == "minecraft:blaze_rod") >= 6), None)
    up = next((r for r in changes if rods and r["elapsed_ms"] >= rods["elapsed_ms"] and r["after"] > r["before"]), None)
    if up is None:
        return None
    # Both positions must come from successive live client ticks, in the same dimension.
    # A slow walk along the backup path also reaches this ledge, without a pearl recall.
    previous = None
    for row in rows:
        if row["event"] != "state":
            continue
        if (previous and previous["dim"] == row["dim"] == "nether"
                and row["run_tick"] == previous["run_tick"] + 1 and row["elapsed_ms"] >= up["elapsed_ms"]):
            point = (row["x"], row["y"], row["z"])
            displacement = math.dist(point, (previous["x"], previous["y"], previous["z"]))
            if displacement > 16 and math.dist(point, (-82.5, 75.5, 179.5)) < 11:
                return {"dropped_to_two_ms": low["elapsed_ms"], "six_rods_observed_ms": rods["elapsed_ms"],
                        "recall_distance_increase_ms": up["elapsed_ms"], "ledge_arrival_ms": row["elapsed_ms"],
                        "recall_displacement_blocks": round(displacement, 2), "recorded_key_changes": len(changes)}
        previous = row
    return None


def _native_shortcut(row):
    before, after = row.get("before"), row.get("after")
    return (type(before) is int and type(after) is int
            and 2 <= min(before, after) and max(before, after) <= 32
            and abs(after - before) == 1
            and row.get("shortcut") == ("F3+F" if after > before else "Shift+F3+F"))


def _item_count(row, item, slot=None):
    return sum(count for index, name, count in row.get("inventory", [])
               if name == item and (slot is None or index == slot))


def _pearl_used(before, after):
    slot = before.get("hotbar_slot")
    pearl = "minecraft:ender_pearl"
    return (type(slot) is int and 0 <= slot <= 8 and slot == after.get("hotbar_slot")
            and isinstance(before.get("inventory"), list) and isinstance(after.get("inventory"), list)
            and not before.get("screen") and not after.get("screen")
            and _item_count(before, pearl, slot) == _item_count(after, pearl, slot) + 1
            and _item_count(before, pearl) == _item_count(after, pearl) + 1)


class MissingStandardStasis(ValueError):
    """A completed attempt did not demonstrate the scored high-ledge recall."""


def require_standard_stasis(rows):
    """Verify the third-throw, six-rod and high-ledge sequence for a scored film."""
    previous = None
    launch = low = rods = rise = restored = None
    distance = None
    changes = 0
    last_ms = -1
    for row in rows:
        if row["elapsed_ms"] < last_ms:
            break
        last_ms = row["elapsed_ms"]
        if row.get("dim") != "nether":
            if launch is not None:
                break
            previous = None
            continue
        event = row.get("event")
        if event == "render_distance_shortcut":
            if not _native_shortcut(row):
                break
            before, after = row["before"], row["after"]
            if launch is None:
                # The controller records its input tick before interaction, then its F3
                # receipts after vanilla accepted the throw and consumed the held pearl.
                if not (before == 16 and after == 15 and previous is not None
                        and previous["run_tick"] == row["run_tick"]
                        and row.get("middle_action", "").split()[:2] == ["throw", "pearl3"]
                        and _pearl_used(previous, row)):
                    continue
                launch = row
                distance = 16
            if (previous["run_tick"] != row["run_tick"] or before != distance
                    or (low is None and after >= before) or restored is not None):
                break
            distance = after
            changes += 1
            if after == 2 and low is None:
                low = row
            if rods is not None:
                if after < before:
                    rise = None
                elif rise is None:
                    rise = row
            if after == 16:
                if rods is None or rise is None:
                    break
                restored = row
        elif event == "state":
            if launch is not None:
                if previous is None or row["run_tick"] != previous["run_tick"] + 1:
                    break
                if previous["run_tick"] > launch["run_tick"] and _pearl_used(previous, row):
                    break
                if (low is not None and rods is None and "inventory" in previous and "inventory" in row
                        and _item_count(previous, "minecraft:blaze_rod") < 6 <= _item_count(row, "minecraft:blaze_rod")):
                    rods = row
                if restored is not None:
                    point = row["x"], row["y"], row["z"]
                    displacement = math.dist(point, (previous["x"], previous["y"], previous["z"]))
                    if displacement > 16 and math.dist(point, (-82.5, 75.5, 179.5)) < 11:
                        return {"initial_distance": 16, "minimum_distance": 2, "recall_distance": 16,
                                "third_pearl_used_ms": launch["elapsed_ms"], "dropped_to_two_ms": low["elapsed_ms"],
                                "six_rods_observed_ms": rods["elapsed_ms"], "recall_distance_increase_ms": rise["elapsed_ms"],
                                "restored_to_sixteen_ms": restored["elapsed_ms"], "ledge_arrival_ms": row["elapsed_ms"],
                                "recall_displacement_blocks": round(displacement, 2), "recorded_key_changes": changes}
            previous = row
    raise MissingStandardStasis("Missing observed third-pearl 16→2→16 shortcut sequence, six rods and high-ledge recall")


def observed_route(rows):
    """Read the full, uninterrupted weekly route from the native observer timeline."""
    if not rows:
        raise ValueError("Missing Week 113 observer timeline")
    first = rows[0]
    if (first.get("run_tick") != 0 or first.get("event") != "spawn"
            or first.get("dim") != "overworld" or first.get("inventory") != []
            or first.get("hp") != 20
            or math.dist([first["x"], first["y"], first["z"]], [207.5, 64, 11.5]) > 3):
        raise ValueError("Expected an empty-inventory natural spawn on the Week 113 beach")
    visited_nether = any(row.get("dim") == "nether" for row in rows)
    expected = ["spawn"] + (["enter_nether", "return_overworld"] if visited_nether else []) + ["enter_end", "credits"]
    for row in rows:
        if row["event"] == "render_distance_shortcut":
            if not _native_shortcut(row):
                raise ValueError("Invalid in-game render distance shortcut receipt")
    transitions = [r["event"] for r in rows if r["event"] not in ("state","render_distance_shortcut")]
    if transitions != expected:
        raise ValueError("Missing the uninterrupted dimension and credits sequence")
    previous = 0
    for row in rows:
        if row["event"] == "state":
            if row["run_tick"] != previous + 1:
                raise ValueError("A native observer tick is missing")
            previous = row["run_tick"]
    return {"observed_ticks": previous, "events": transitions,
            "stasis_shortcuts": require_standard_stasis(rows)}


def verify_route(world, rows):
    """Bind the recorded route to the native player's advancement and stats files."""
    if (world / "calibration-only").exists():
        raise ValueError("A calibration world cannot qualify for the weekly film")
    observed = observed_route(rows)
    native = read_level(world / "level.dat")
    player = native["Player"]
    raw_id = player.get("UUID")
    if not isinstance(raw_id, bytes) or len(raw_id) != 16:
        raise ValueError("Missing native player UUID")
    player_id = str(uuid.UUID(bytes=raw_id))
    advancements = json.loads((world / "advancements" / (player_id + ".json")).read_text(encoding="utf-8"))
    required = ["minecraft:story/enter_the_end", "minecraft:end/kill_dragon"]
    if "enter_nether" in observed["events"]:
        required.insert(0,"minecraft:story/enter_the_nether")
    if not all(advancements.get(adv, {}).get("done") is True for adv in required):
        raise ValueError("The native player does not have the required dimension and dragon advancements")
    stats = json.loads((world / "stats" / (player_id + ".json")).read_text(encoding="utf-8"))
    deaths = stats["stats"].get("minecraft:custom", {}).get("minecraft:deaths", 0)
    if deaths:
        raise ValueError("The native player died during this attempt")
    return {"source": "saved vanilla advancements and stats matched to player UUID",
            "advancements": required, "deaths": deaths, "initial_inventory": [], **observed}
