#!/usr/bin/env python3
"""Create a new, isolated Minecraft 1.16.1 singleplayer save with an original practice course."""
import argparse
import gzip
import os
from pathlib import Path
import shutil
import struct
import tempfile

COURSE = Path(__file__).resolve().parents[1] / "course"
WORLD_NAME = "AI-Speedrun-Arena-Lab"


def string(value):
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def payload(kind, value):
    if kind in (1, 3, 4):
        return struct.pack({1: ">b", 3: ">i", 4: ">q"}[kind], value)
    if kind == 8:
        return string(value)
    if kind == 9:
        entry_type, entries = value
        return bytes([entry_type]) + struct.pack(">i", len(entries)) + b"".join(payload(entry_type, entry) for entry in entries)
    if kind == 10:
        return b"".join(bytes([t]) + string(name) + payload(t, v) for name, (t, v) in value.items()) + b"\0"
    raise ValueError(f"Unsupported NBT tag {kind}")


def level_bytes():
    seed = 16101
    # StructureConfig requires a "structures" map. This 1.16.1 superflat
    # preset has no naturally generated structures.
    layers = [{"height": (3, h), "block": (8, "minecraft:" + name)}
              for h, name in [(1, "bedrock"), (2, "dirt"), (1, "grass_block")]]
    flat = {"type": (8, "minecraft:flat"), "settings": (10, {
        "biome": (8, "minecraft:plains"), "layers": (9, (10, layers)),
        "structures": (10, {"structures": (10, {})})})}
    def noise(settings, biome):
        return {"type": (8, "minecraft:noise"), "seed": (4, seed), "settings": (8, settings),
                "biome_source": (10, biome)}
    nether = noise("minecraft:nether", {"type": (8, "minecraft:multi_noise"), "seed": (4, seed),
                                        "preset": (8, "minecraft:nether")})
    end = noise("minecraft:end", {"type": (8, "minecraft:the_end"), "seed": (4, seed)})
    dims = {"minecraft:" + n: (10, {"type": (8, "minecraft:" + n), "generator": (10, g)})
            for n, g in [("overworld", flat), ("the_nether", nether), ("the_end", end)]}
    data = {"DataVersion": (3, 2567), "version": (3, 19133),
            "Version": (10, {"Id": (3, 2567), "Name": (8, "1.16.1"), "Snapshot": (1, 0)}),
            "LevelName": (8, "AI Speedrun Arena | Practice Lab"), "GameType": (3, 0), "allowCommands": (1, 1),
            "initialized": (1, 1), "hardcore": (1, 0), "Difficulty": (1, 0), "Time": (4, 0),
            "DayTime": (4, 6000), "SpawnX": (3, 0), "SpawnY": (3, 65), "SpawnZ": (3, 0),
            "WorldGenSettings": (10, {"seed": (4, seed), "generate_features": (1, 0),
                                      "bonus_chest": (1, 0), "dimensions": (10, dims)}),
            "DataPacks": (10, {"Enabled": (9, (8, ["vanilla", "file/arena_course"])),
                                "Disabled": (9, (8, []))})}
    return gzip.compress(b"\x0a\0\0" + payload(10, {"Data": (10, data)}), mtime=0)


def prepare(game_dir):
    saves = Path(game_dir).expanduser() / "saves"
    saves.mkdir(parents=True, exist_ok=True)
    target = saves / WORLD_NAME
    if target.exists():
        raise FileExistsError(f"练习存档已经存在，已保留原内容：{target}")
    with tempfile.TemporaryDirectory(dir=saves) as parent:
        staging = Path(parent) / WORLD_NAME
        staging.mkdir()
        (staging / "level.dat").write_bytes(level_bytes())
        shutil.copytree(COURSE, staging / "datapacks" / "arena_course")
        staging.rename(target)
    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", type=Path, default=os.environ.get("ARENA_GAME_DIR", "~/.minecraft"))
    args = parser.parse_args()
    try:
        print(prepare(args.game_dir))
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")
