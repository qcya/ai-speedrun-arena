#!/usr/bin/env python3
"""Prepare one local Java Edition 1.16.1 instance for native Chinese capture."""
import argparse
import json
import shutil
import zipfile
from pathlib import Path

PACK = "Astra-local-skin.zip"


def set_options(path, values):
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    existing = dict(line.split(":", 1) for line in lines if ":" in line)
    existing.update(values)
    path.write_text("".join(f"{key}:{value}\n" for key, value in existing.items()), encoding="utf-8")


def prepare(instance, skin, model, route, rules, jar, history=None):
    from PIL import Image
    instance.mkdir(parents=True, exist_ok=True)
    with Image.open(skin) as image:
        if image.format != "PNG" or image.size != (64, 64):
            raise ValueError("Provide an original 64 by 64 PNG skin")
        rgba = image.convert("RGBA")
        if model == "auto":
            alpha = rgba.getchannel("A")
            slim = all(alpha.crop(box).getbbox() is None for box in ((54,20,56,32),(46,52,48,64)))
            model = "slim" if slim else "default"
    if not jar.is_file():
        raise FileNotFoundError(f"Presentation mod missing: {jar}")
    mods = instance / "mods"
    mods.mkdir(exist_ok=True)
    shutil.copyfile(jar, mods / "astra-local-film.jar")
    packs = instance / "resourcepacks"
    packs.mkdir(exist_ok=True)
    with zipfile.ZipFile(packs/PACK, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("pack.mcmeta", json.dumps({"pack":{"pack_format":5,"description":"Astra local practice skin"}}))
        archive.write(skin, "assets/astra_film/textures/local_skin.png")
    options = instance/"options.txt"
    previous = dict(line.split(":",1) for line in options.read_text(encoding="utf-8").splitlines() if ":" in line) if options.exists() else {}
    order = json.loads(previous.get("resourcePacks", '["vanilla"]'))
    pack_name = "file/" + PACK
    order = [p for p in order if p != pack_name] + [pack_name]
    set_options(options, {"lang":"zh_cn","resourcePacks":json.dumps(order,separators=(",",":")),
                "version":"2567","guiScale":"2","fullscreen":"false","overrideWidth":"1920",
                "overrideHeight":"1080","maxFps":"120","enableVsync":"false","pauseOnLostFocus":"false",
                "renderDistance":"5","graphicsMode":"0","fancyGraphics":"false","clouds":"false","tutorialStep":"none"})
    config = instance/"config"
    config.mkdir(exist_ok=True)
    profile = {"model":model,"route":route,"rules":rules}
    if history is not None:
        profile["history"] = str(history.expanduser().resolve())
    (config/"astra-film.json").write_text(json.dumps(profile,
                                            ensure_ascii=False, indent=2)+"\n",encoding="utf-8")
    # SpeedRunIGT's supported cosmetic switch prevents its separate clock covering this HUD.
    optpath=instance/"speedrunigt"/"options.txt"
    optpath.parent.mkdir(exist_ok=True)
    existing=optpath.read_text(encoding="utf-8").splitlines() if optpath.exists() else []
    existing=[s for s in existing if not s.startswith("speedrunigt:toggle_timer:")]
    optpath.write_text("\n".join(existing+["speedrunigt:toggle_timer:false"])+"\n",encoding="utf-8")
    return model


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--instance",required=True,type=Path)
    p.add_argument("--skin",required=True,type=Path)
    p.add_argument("--skin-model",choices=["auto","slim","default"],default="auto")
    p.add_argument("--route",choices=["nether","direct"],default="nether")
    p.add_argument("--rules",required=True,help="Course identity including the weekly ID and rules version")
    p.add_argument("--jar",type=Path,default=Path(__file__).with_name("astra-local-film.jar"))
    p.add_argument("--history-dir",type=Path,help="Shared private PB receipts directory for fresh isolated instances")
    a=p.parse_args()
    print("Local skin model:",prepare(a.instance.expanduser(),a.skin,a.skin_model,a.route,a.rules,a.jar,a.history_dir))


if __name__=="__main__":
    main()
