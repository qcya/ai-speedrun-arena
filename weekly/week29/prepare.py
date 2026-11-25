#!/usr/bin/env python3
"""Install the optional mod and create a fresh, separate local survival save."""
import argparse
import os
from pathlib import Path
import shutil
import tempfile

ROOT = Path(__file__).resolve().parent

def prepare(game_dir, world_name):
    game = game_dir.expanduser().resolve()
    if not world_name or Path(world_name).name != world_name or world_name in (".", ".."):
        raise ValueError("存档名称需要使用单个文件夹名")
    saves, mods = game / "saves", game / "mods"
    saves.mkdir(parents=True, exist_ok=True)
    mods.mkdir(exist_ok=True)
    target = saves / world_name
    if target.exists():
        raise FileExistsError(f"存档已存在，可用 --world-name 指定新名称：{target}")
    source = ROOT / "ai-speedrun-week29-0.1.0.jar"
    installed = mods / source.name
    if installed.exists() and installed.read_bytes() != source.read_bytes():
        raise FileExistsError(f"实例中已有同名的其他版本：{installed}")
    with tempfile.TemporaryDirectory(dir=saves) as tmp:
        new = Path(tmp) / "save"
        new.mkdir()
        shutil.copyfile(ROOT / "data/level.dat", new / "level.dat")
        shutil.copyfile(ROOT / "data/route.json", new / "survival-route.json")
        (new / "astra-week29.enabled").touch()
        if not installed.exists():
            with tempfile.NamedTemporaryFile(dir=mods, delete=False) as f:
                temp_mod = Path(f.name)
            try:
                shutil.copyfile(source, temp_mod)
                os.replace(temp_mod, installed)
            finally:
                if temp_mod.exists():
                    temp_mod.unlink()
        new.rename(target)
    return target

if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--game-dir", type=Path, required=True)
    p.add_argument("--world-name", default="ASTRA-Week29")
    args = p.parse_args()
    try:
        print(prepare(args.game_dir, args.world_name))
    except (OSError, ValueError) as exc:
        p.exit(1, f"{exc}\n")
