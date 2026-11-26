#!/usr/bin/env python3
"""Render the recorded Week 113 winning take on a local machine with Prism Launcher."""
import argparse
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
from urllib.error import URLError
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT=Path(__file__).resolve().parent
WEEK=ROOT/"weekly/week113"
DEFAULT_TAKE=WEEK/"release"/"Astra-W113.zip"

def load_helper(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module

def find_prism(provided):
    if provided:return Path(provided).expanduser().resolve()
    win=Path(os.environ.get("LOCALAPPDATA","."));pf=Path(os.environ.get("ProgramFiles","."))
    for path in [shutil.which("prismlauncher"),shutil.which("prismlauncher.exe"),
                 win/"Programs/PrismLauncher/prismlauncher.exe",pf/"PrismLauncher/prismlauncher.exe"]:
        if path and Path(path).is_file():return Path(path)
    raise ValueError("Install Prism Launcher or supply --prism (Windows: winget install PrismLauncher.PrismLauncher)")

def appdata_dir():
    if os.name=="nt":return Path(os.environ["APPDATA"])/"PrismLauncher"
    if sys.platform=="darwin":return Path.home()/"Library/Application Support/PrismLauncher"
    flat=Path.home()/".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"
    return flat if flat.is_dir() else Path.home()/".local/share/PrismLauncher"

def verify_take(path, allow_segment):
    if not path.is_file():
        raise ValueError(f"The repository contains no verified first-place-standard full take at {path.name}. No film rendered.")
    with zipfile.ZipFile(path) as z:
        if len(z.namelist())!=len(set(z.namelist())):raise ValueError("Repeated ZIP member")
        bad=z.testzip()
        if bad:raise ValueError("Corrupt ZIP member: "+bad)
        manifest=dict((name,checksum) for checksum,name in (line.split("  ",1) for line in z.read("SHA256SUMS").decode().splitlines()))
        required={"Astra.mcpr","observer.jsonl.gz","native-timer.json","take.json","course.json"}
        if not allow_segment:required.update({"render-assets/splits.json","render-assets/astra-film.json","render-assets/Astra-local-skin.zip"})
        if not required.issubset(manifest):raise ValueError("Incomplete recording package")
        for name,expected in manifest.items():
            h=hashlib.sha256()
            with z.open(name) as f:
                for chunk in iter(lambda:f.read(1024*1024),b""):h.update(chunk)
            if h.hexdigest()!=expected:raise ValueError("Incorrect recording hash: "+name)
        meta=json.loads(z.read("take.json")); data=json.loads(z.read("native-timer.json"))
        course=json.loads(z.read("course.json"));pb=course['top_five'][0]['time_ms']
        pinned=json.loads((WEEK/"course.json").read_text(encoding="utf-8"))
        for key in ("week","minecraft","ranked","replaymod","seed","world_template_sha256"):
            if course.get(key)!=pinned[key]:raise ValueError(f"Recording differs from pinned course: {key}")
        if meta.get("course")!=113 or course["week"]!=113:raise ValueError("Unexpected course")
        if not allow_segment and not (meta.get("kind")=="local_full_run" and meta.get("native_completed") is True and
                data.get("is_completed") is True and 0<meta["last_observed_ms"]<600000 and 0<data["final_igt"]<=pb and meta.get("native_route") and
                meta.get("native_world",{}).get("seen_credits") is True):
            raise ValueError("This take contains no verified sub-ten-minute run matching or improving the recorded champion IGT")
        return meta,course

def copy_member(z,name,path):
    path.parent.mkdir(parents=True,exist_ok=True)
    with z.open(name) as src,path.open("wb") as out:shutil.copyfileobj(src,out)

def render(a):
    take=a.take.resolve();dst=a.output.resolve()
    meta,course=verify_take(take,a.practice_segment)
    if a.check:
        print(json.dumps(meta,ensure_ascii=False,indent=2));return
    if dst.exists():raise FileExistsError(dst)
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        raise ValueError("Install FFmpeg + ffprobe and add to PATH (Windows: winget install Gyan.FFmpeg)")
    prism=find_prism(a.prism)
    data=(a.prism_data or appdata_dir()).resolve()
    if not (data/"instances").is_dir():raise ValueError("Open Prism Launcher once or specify --prism-data for the existing installation")
    music_source=load_helper("film_music",ROOT/"presentation/music.py")
    if a.music is not None:
        a.music=a.music.expanduser().resolve()
        if not a.music.is_file():raise FileNotFoundError(a.music)
    elif not a.practice_segment:
        a.music=music_source.local_source([Path.home()/name for name in ("Music","Downloads","Videos","Desktop")]+[ROOT/"render-assets"])
    prepare=load_helper("w113prepare",WEEK/"prepare.py")
    film_prep=load_helper("film_prep",ROOT/"presentation/prepare.py")
    native=load_helper("replay_pov",ROOT/"presentation/replay_pov.py")
    dst.parent.mkdir(parents=True,exist_ok=True)
    dstroot=Path(tempfile.mkdtemp(prefix="Astra-Film-",dir=data/"instances"))
    game=dstroot/".minecraft";mods=game/"mods";mods.mkdir(parents=True)
    cfg={"formatVersion":1,"components":[{"uid":"net.minecraft","version":"1.16.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.14"}]}
    (dstroot/"mmc-pack.json").write_text(json.dumps(cfg,indent=2));(dstroot/"instance.cfg").write_text("[General]\nInstanceType=OneSix\nname=Astra W113 Film\n")
    # Keep a dedicated renderer and journal for diagnosis; never modify the user's play instance.
    print("Render instance:",dstroot,flush=True)
    for key,local_jar in [("replaymod",a.replay_jar),("ranked",a.ranked_jar)]:
        renderer_jar=prepare.dependency(course[key],local_jar,game)
        shutil.copyfile(renderer_jar,mods/course[key]["filename"])
    shutil.copyfile(ROOT/"presentation/astra-local-film.jar",mods/"astra-local-film.jar")
    with zipfile.ZipFile(take) as z:
        hud=json.loads(z.read("render-assets/splits.json")) if "render-assets/splits.json" in z.namelist() else None
        copy_member(z,"Astra.mcpr",game/"original.mcpr")
        with z.open("observer.jsonl.gz") as packed,gzip.GzipFile(fileobj=packed) as f,(game/"observer.jsonl").open("wb") as out:
            shutil.copyfileobj(f,out)
        if a.skin:
            film_prep.prepare(game,a.skin,"auto","nether","week113-6.0.12-flags207",ROOT/"presentation/astra-local-film.jar")
        elif "render-assets/Astra-local-skin.zip" in z.namelist():
            pack="Astra-local-skin.zip"
            copy_member(z,"render-assets/"+pack,game/"resourcepacks"/pack)
            film_prep.set_options(game/"options.txt",{"lang":"zh_cn","guiScale":"2","resourcePacks":json.dumps(["vanilla","file/"+pack])})
            film_config=game/"config";film_config.mkdir(exist_ok=True)
            if "render-assets/astra-film.json" in z.namelist():copy_member(z,"render-assets/astra-film.json",film_config/"astra-film.json")
        else:
            film_prep.set_options(game/"options.txt",{"lang":"zh_cn","guiScale":"2"})
    marked=game/"replay_recordings"/"Astra-pov.mcpr";marked.parent.mkdir(exist_ok=True)
    source=game/"original.mcpr";journal=game/"observer.jsonl"
    view=native.build(source,journal,marked,"Astra",meta.get("spawn_minus_replay_start_ms"),0,None,
                      hud=hud,credits_tail_ms=18000 if meta["kind"]=="local_full_run" else 0)
    cfgdir=game/"config";cfgdir.mkdir(exist_ok=True)
    raw=game/"Astra-native.mp4";(cfgdir/"astra-render-once.json").write_text(json.dumps({"input":str(marked),"output":str(raw),"ffmpeg":str(Path(shutil.which("ffmpeg")).resolve())}))
    launcher=subprocess.Popen([str(prism),"-d",str(data),"-l",dstroot.name],cwd=dstroot)
    expected=round(view["video_duration_ms"]*.06)
    last_ms=-1
    last_change=time.monotonic()
    signature=None
    while not (cfgdir/"astra-render-done.json").exists():
        failure=cfgdir/"astra-render-failed.txt"
        if failure.exists():raise ValueError(failure.read_text(encoding="utf-8"))
        if launcher.poll() not in (None,0):raise ValueError(f"Prism exited with {launcher.returncode}; inspect {game / 'logs'}")
        progress=cfgdir/"astra-render-progress.json"
        if progress.is_file():
            try:
                advance=json.loads(progress.read_bytes());current=advance["rendered_ms"]
                if current!=last_ms:
                    last_ms=current
                    print(f"Native 1080p60: {current/1000:.1f} / {advance['duration_ms']/1000:.1f} s",flush=True)
            except (OSError,ValueError,KeyError):pass
        paths=[raw,progress,game/"logs/latest.log",cfgdir/"astra-render-active.json"]
        state=tuple((p.stat().st_size,p.stat().st_mtime_ns) if p.exists() else None for p in paths)
        if signature!=state:signature=state;last_change=time.monotonic()
        elif time.monotonic()-last_change>a.idle_minutes*60:
            raise ValueError(f"No launcher or render activity for {a.idle_minutes:g} minutes; inspect {game / 'logs'}. The instance and recording are preserved.")
        time.sleep(3)
    probe=subprocess.run(["ffprobe","-v","error","-show_streams","-of","json",str(raw)],capture_output=True,check=True)
    v=next((q for q in json.loads(probe.stdout).get("streams",[]) if q["codec_type"]=="video"),{})
    if (v.get("width"),v.get("height"))!=(1920,1080) or v.get("avg_frame_rate") not in ("60/1","60"):
        raise ValueError("Native movie differs from 1920x1080 at 60 fps")
    frames=int(v.get("nb_frames",0))
    if abs(frames-expected)>1:raise ValueError(f"Expected {expected} frames, received {frames}")
    with tempfile.TemporaryDirectory(dir=dst.parent) as td:
        if not a.music and not a.practice_segment:
            try:a.music=music_source.artist_source(Path(td)/"Sleepwalker.mp3")
            except (URLError,ValueError,OSError) as e:
                raise ValueError(f"The native render is preserved at {raw}. To mux it supply your local Sleepwalker track; artist source returned {e}") from e
        tmp=Path(td)/"completed.mp4"
        if a.music:
            cmd=["ffmpeg","-v","error","-nostdin","-i",str(raw),"-stream_loop","-1","-i",str(a.music),"-map","0:v:0","-map","1:a:0",
                 "-c:v","copy","-c:a","aac","-b:a","192k","-t",f"{frames/60:.8f}","-movflags","+faststart",str(tmp)]
            subprocess.run(cmd,check=True)
        else:shutil.copyfile(raw,tmp)
        if dst.exists():raise FileExistsError(dst)
        # The temporary directory and destination share a filesystem.
        if os.name=="nt":os.rename(tmp,dst)
        else:os.link(tmp,dst)
    print(dst)

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--take",type=Path,default=DEFAULT_TAKE);parser.add_argument("--output",type=Path,default=ROOT/"Astra-W113-1080p60.mp4")
    parser.add_argument("--idle-minutes",type=float,default=20,help="Stop waiting after this many minutes without launcher, frame, or output activity")
    parser.add_argument("--check",action="store_true");parser.add_argument("--practice-segment",action="store_true")
    parser.add_argument("--prism",type=Path);parser.add_argument("--prism-data",type=Path);parser.add_argument("--skin",type=Path)
    parser.add_argument("--music",type=Path);parser.add_argument("--replay-jar",type=Path);parser.add_argument("--ranked-jar",type=Path)
    try:render(parser.parse_args())
    except (OSError,ValueError,KeyError,zipfile.BadZipFile,subprocess.SubprocessError) as exc:parser.exit(1,str(exc)+"\n")
