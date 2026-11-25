#!/usr/bin/env python3
"""Bundle the built bridge and the original training save as a Prism/MultiMC instance."""
import argparse
import json
from pathlib import Path
import tempfile
from zipfile import ZipFile, ZIP_DEFLATED
from prepare_course import prepare


def build(jar, output):
    with ZipFile(jar) as mod:
        metadata = json.loads(mod.read("fabric.mod.json"))
        if metadata.get("id") != "ai_speedrun_arena":
            raise ValueError("选择的 JAR 不是 Arena 的客户端模组")
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        root = tmp / "AI-Speedrun-Arena"
        game = root / ".minecraft"
        prepare(game)
        (game / "mods").mkdir()
        (game / "mods" / jar.name).write_bytes(jar.read_bytes())
        (root / "instance.cfg").write_text("[General]\nInstanceType=OneSix\nname=AI Speedrun Arena\n", encoding="utf-8")
        pack = {"formatVersion": 1, "components": [
            {"uid": "net.minecraft", "version": "1.16.1"},
            {"uid": "net.fabricmc.fabric-loader", "version": "0.16.14"}]}
        (root / "mmc-pack.json").write_text(json.dumps(pack, indent=2), encoding="utf-8")
        output.parent.mkdir(parents=True, exist_ok=True)
        with ZipFile(output, "w", compression=ZIP_DEFLATED) as dest:
            for file in sorted(root.rglob("*")):
                if file.is_file():
                    dest.write(file, file.relative_to(tmp))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("jar", type=Path)
    p.add_argument("output", type=Path)
    a = p.parse_args()
    build(a.jar, a.output)
