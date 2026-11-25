#!/usr/bin/env python3
"""Install the historical Week 29 local practice rules into a fresh prepared save."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import tempfile
import zipfile

BASE_SHA256 = "71e610bc2bd07dbec8513af7918b5756231c391a7f07386eb0afaf4afe1eec20"
PROFILE_SHA256 = "670932b263903dbecd6af27ef38b8af6b4c70f19d84d34f457a6b5ba69162246"
JAR_SHA512 = "a9e24b3c7f0a0d75736de0094353e405ce2928e545d7f8b4538f448df8231144bb7b5c460af5a3449c9bc417f3afc554d11f578bb202c7cab0bed392e42be8b6"
MOD_URL = "https://modrinth.com/mod/mcsr-ranked/version/5.0.12"
RULES = {
    "removeSpawnBlockEntities": False,
    "artificialLavaPool": False,
    "noMonsterInBastion": True,
    "standardDragonPath": True,
    "lowerDragonFirstNode": False,
    "modifiedLootTables": False,
    "instantDragonPerch": True,
}
SEEDS = {
    "nether_seed": -392129629567131977,
    "the_end_seed": 4056954346358420256,
    "rng_seed": 7470996919763249519,
}


def nbt_name(value):
    raw = value.encode("ascii")
    return struct.pack(">H", len(raw)) + raw


def template(base):
    if hashlib.sha256(base).hexdigest() != BASE_SHA256:
        raise ValueError("Expected the unplayed original Week 29 world template")
    original = gzip.decompress(base)
    data = b"\x0a" + nbt_name("Data")
    generator = b"\x0a" + nbt_name("WorldGenSettings")
    gamerules = b"\x0a" + nbt_name("GameRules")
    for key, enabled in RULES.items():
        gamerules += b"\x08" + nbt_name(key) + nbt_name(str(enabled).lower())
    gamerules += b"\0"
    seed_tags = b"".join(b"\x04" + nbt_name(key) + struct.pack(">q", value)
                         for key, value in SEEDS.items())
    expected = original.replace(data, data + gamerules).replace(generator, generator + seed_tags)
    packaged = Path(__file__).with_name("level.dat").read_bytes()
    if hashlib.sha256(packaged).hexdigest() != PROFILE_SHA256 or gzip.decompress(packaged) != expected:
        raise ValueError("The included historical profile template failed verification")
    return packaged


def install(game_dir, world_name, ranked_jar):
    if not world_name or Path(world_name).name != world_name or world_name in (".", ".."):
        raise ValueError("Supply a single fresh world folder name")
    if hashlib.sha512(ranked_jar.read_bytes()).hexdigest() != JAR_SHA512:
        raise ValueError("The official JAR must match the author's published 5.0.12")
    game_dir = game_dir.expanduser().resolve()
    world = game_dir / "saves" / world_name
    if not (world / "astra-week29.enabled").is_file() or not (world / "survival-route.json").is_file():
        raise ValueError("First create an unplayed Week 29 save using prepare.py")
    if any((world / name).exists() for name in ("region", "session.lock", "playerdata", "observed-run.jsonl")):
        raise ValueError("Use this profile before opening the fresh world")
    data_path = world / "level.dat"
    current = data_path.read_bytes()
    if hashlib.sha256(current).hexdigest() == PROFILE_SHA256:
        data = template((Path(__file__).resolve().parent.parent / "data" / "level.dat").read_bytes())
    else:
        data = template(current)
    mods = game_dir / "mods"
    mods.mkdir(exist_ok=True)
    existing = []
    for other in mods.glob("*.jar"):
        with zipfile.ZipFile(other) as archive:
            if "fabric.mod.json" in archive.namelist():
                descriptor = json.loads(archive.read("fabric.mod.json"))
                if descriptor.get("id") == "mcsrranked":
                    existing.append(other)
    if len(existing) > 1 or any(hashlib.sha512(item.read_bytes()).hexdigest() != JAR_SHA512 for item in existing):
        raise ValueError("Use a separate local instance with a single copy of Ranked 5.0.12")
    if not existing:
        target = mods / "mcsrranked-5.0.12.jar"
        with tempfile.NamedTemporaryFile(dir=mods, delete=False) as tmp:
            tmp_name = Path(tmp.name)
        try:
            shutil.copyfile(ranked_jar, tmp_name)
            try:
                os.link(tmp_name, target)
            except FileExistsError:
                if hashlib.sha512(target.read_bytes()).hexdigest() != JAR_SHA512:
                    raise
        finally:
            tmp_name.unlink(missing_ok=True)
    if current == data:
        return PROFILE_SHA256
    with tempfile.NamedTemporaryFile(dir=world, delete=False) as tmp:
        tmp.write(data)
        tmp_name = Path(tmp.name)
    try:
        os.replace(tmp_name, data_path)
    finally:
        tmp_name.unlink(missing_ok=True)
    return PROFILE_SHA256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", required=True, type=Path)
    parser.add_argument("--world-name", required=True)
    parser.add_argument("--ranked-jar", required=True, type=Path, help=MOD_URL)
    opts = parser.parse_args()
    try:
        print(install(opts.game_dir, opts.world_name, opts.ranked_jar.expanduser()))
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
