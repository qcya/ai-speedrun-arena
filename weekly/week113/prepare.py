#!/usr/bin/env python3
"""Prepare a separate local Week 113 instance with the checked map and replay capture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parent
REPO=ROOT.parents[1]

def filehash(p,algorithm):
    h=hashlib.new(algorithm)
    with p.open("rb") as f:
        for part in iter(lambda:f.read(1024*1024),b""):h.update(part)
    return h.hexdigest()

def dependency(info, supplied, work):
    if supplied:
        path=Path(supplied).expanduser()
    else:
        path=work/info["filename"]
        req=urllib.request.Request(info["url"],headers={"User-Agent":"Astra-local-Minecraft-practice/1"})
        with urllib.request.urlopen(req,timeout=90) as r,path.open("wb") as w:shutil.copyfileobj(r,w)
    if filehash(path,"sha512")!=info["hashes"]["sha512"]:raise ValueError(f"Hash mismatch: {info['filename']}")
    return path

def prepare(args):
    course=json.loads((ROOT/"course.json").read_text())
    instance=args.game_dir.expanduser().resolve()
    if not re.fullmatch("[A-Za-z0-9_-]{1,80}",args.world_name):raise ValueError("Use a simple world folder name")
    target=instance/"saves"/args.world_name
    if target.exists():raise ValueError(f"World already exists: {target}")
    if filehash(ROOT/"level.dat","sha256")!=course["world_template_sha256"]:raise ValueError("Invalid course template")
    mod=ROOT/"astra-week113.jar"
    if not mod.is_file():raise FileNotFoundError("Build or download the repository's astra-week113.jar")
    if args.skin and not args.skin.is_file():raise FileNotFoundError(args.skin)
    mods=instance/"mods";mods.mkdir(parents=True,exist_ok=True);target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(dir=instance) as d:
        tmp=Path(d)
        binaries={mod.name:mod}
        for key,input_file in [("ranked",args.ranked_jar),("replaymod",args.replay_jar)]:
            info=course[key];binaries[info["filename"]]=dependency(info,input_file,tmp)
        known={}
        for filename,path in binaries.items():
            with zipfile.ZipFile(path) as z:known[json.loads(z.read("fabric.mod.json"))["id"]]=(filename,path)
        installed=set()
        for existing in mods.glob("*.jar"):
            with zipfile.ZipFile(existing) as z:
                if "fabric.mod.json" not in z.namelist():continue
                ident=json.loads(z.read("fabric.mod.json")).get("id")
            if ident not in known:continue
            filename,source=known[ident]
            if ident in installed or filehash(source,"sha256")!=filehash(existing,"sha256"):
                raise ValueError(f"Conflicting installed {ident}; use a separate instance for this build")
            installed.add(ident)
        staged=tmp/"newworld";staged.mkdir();shutil.copyfile(ROOT/"level.dat",staged/"level.dat")
        (staged/"astra-113.local").touch()
        if getattr(args,"upper_corridor",False):
            (staged/"astra-bastion-upper").touch()
        if getattr(args,"wait_for_piglins",False):
            (staged/"astra-bastion-wait").touch()
        if getattr(args,"eastern",False):
            (staged/"astra-opening-east").touch()
        if getattr(args,"save_gold",False):
            (staged/"astra-opening-save-gold").touch()
        if getattr(args,"shield",False):
            (staged/"astra-opening-shield").touch()
        if args.skin:
            subprocess.run([sys.executable,str(REPO/"presentation/prepare.py"),"--instance",str(instance),
                "--skin",str(args.skin),"--route","nether","--rules","week113-6.0.12-flags207"],check=True)
        for ident,(filename,path) in known.items():
            if ident not in installed:
                staged_jar=tmp/("checked-"+filename);shutil.copyfile(path,staged_jar)
                os.link(staged_jar,mods/filename)
        staged.rename(target)
    if args.autostart:
        (instance/"astra-local-autostart.txt").write_text(args.world_name,encoding="utf-8")
    print(target)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--upper-corridor",action="store_true",help="Use the elevated bastion detour if the usual lower corridor is crowded")
    p.add_argument("--wait-for-piglins",action="store_true",help="Wait on the first chest ledge for a crowded group to calm; use the lower bastion corridor")
    p.add_argument("--save-gold",action="store_true",help="Reserve the treasure gold for piglins and use surplus iron for the shovel when available")
    p.add_argument("--shield",action="store_true",help="Use the wooden TNT trigger and equip a shield before entering the Nether")
    p.add_argument("--game-dir",type=Path,required=True);p.add_argument("--world-name",default="Astra-W113")
    p.add_argument("--skin",type=Path);p.add_argument("--ranked-jar",type=Path);p.add_argument("--replay-jar",type=Path)
    p.add_argument("--autostart",action="store_true",help="Enter this fresh local world at the title screen")
    try:prepare(p.parse_args())
    except (OSError,ValueError,subprocess.CalledProcessError,zipfile.BadZipFile) as e:p.exit(1,f"{e}\n")
