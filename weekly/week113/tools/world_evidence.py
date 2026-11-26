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


def verify_route(world, rows):
    """Bind a qualifying uninterrupted run to the native player's advancement file."""
    first = rows[0]
    if (world / "calibration-only").exists():
        raise ValueError("A calibration world cannot qualify for the weekly film")
    if (first.get("run_tick") != 0 or first.get("event") != "spawn"
            or first.get("dim") != "overworld" or first.get("inventory") != []
            or first.get("hp") != 20
            or math.dist([first["x"], first["y"], first["z"]], [207.5, 64, 11.5]) > 3):
        raise ValueError("Expected an empty-inventory natural spawn on the Week 113 beach")
    expected = iter(["spawn", "enter_nether", "return_overworld", "enter_end", "credits"])
    transitions = [r["event"] for r in rows if r["event"] != "state"]
    if transitions != list(expected):
        raise ValueError("Missing the uninterrupted dimension and credits sequence")
    previous = 0
    for row in rows:
        if row["event"] == "state":
            if row["run_tick"] != previous + 1:
                raise ValueError("A native observer tick is missing")
            previous = row["run_tick"]
    native = read_level(world / "level.dat")
    player = native["Player"]
    raw_id = player.get("UUID")
    if not isinstance(raw_id, bytes) or len(raw_id) != 16:
        raise ValueError("Missing native player UUID")
    player_id = str(uuid.UUID(bytes=raw_id))
    advancements = json.loads((world / "advancements" / (player_id + ".json")).read_text(encoding="utf-8"))
    required = ["minecraft:story/enter_the_nether", "minecraft:story/enter_the_end", "minecraft:end/kill_dragon"]
    if not all(advancements.get(adv, {}).get("done") is True for adv in required):
        raise ValueError("The native player does not have the required dimension and dragon advancements")
    stats = json.loads((world / "stats" / (player_id + ".json")).read_text(encoding="utf-8"))
    deaths = stats["stats"].get("minecraft:custom", {}).get("minecraft:deaths", 0)
    if deaths:
        raise ValueError("The native player died during this attempt")
    return {"source": "saved vanilla advancements and stats matched to player UUID",
            "advancements": required, "deaths": deaths, "initial_inventory": [],
            "observed_ticks": previous, "events": transitions}
