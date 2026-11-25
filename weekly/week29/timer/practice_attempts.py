#!/usr/bin/env python3
"""Launch isolated Linux practice attempts and keep real split records across retries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time

from split_timer import LABELS, best_times, classify, read_attempt


def json_out(path, value):
    temp=path.with_suffix(".tmp")
    temp.write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n")
    temp.replace(path)


def load_config(path):
    cfg=json.loads(path.read_text())
    course=cfg["course"]
    if not isinstance(course,dict) or not all(course.get(k) for k in ("version","seeds","template_sha256","rules")):
        raise ValueError("course needs version, seeds, template_sha256, rules")
    if not isinstance(course["seeds"],dict) or "minecraft:overworld" not in course["seeds"]:
        raise ValueError("Specify actual dimension seeds as strings")
    if not all(isinstance(seed,str) and seed.lstrip("-").isdigit() for seed in course["seeds"].values()):
        raise ValueError("Dimension seeds must be integer strings")
    digest=course["template_sha256"]
    if len(digest)!=64 or any(c not in "0123456789abcdef" for c in digest):
        raise ValueError("template_sha256 must be the SHA-256 of the fresh starting level.dat")
    template=Path(cfg["world_template"])
    if not template.is_absolute():template=path.parent/template
    if hashlib.sha256(template.read_bytes()).hexdigest()!=digest:raise ValueError("Initial world template hash mismatch")
    cfg["world_template"]=str(template.resolve())
    for key in ("mod","itinerary"):
        if key in cfg:
            local=Path(cfg[key])
            if not local.is_absolute():local=path.parent/local
            if not local.is_file():raise ValueError(f"Missing {key}: {local}")
            cfg[key]=str(local.resolve())
    if ("mod" in cfg)!=("itinerary" in cfg):raise ValueError("Specify both mod and itinerary for the Week 29 runner")
    if cfg["route"] not in ("direct","nether"):raise ValueError("route must be direct or nether")
    gates=cfg.get("gates",[])
    if any(k not in LABELS for k in gates) or (cfg["route"]=="direct" and "enter_nether" in gates):
        raise ValueError("A gate must be an observed milestone on the chosen route")
    for key in ["launch","finalize"]:
        cmd=cfg.get(key)
        if key=="finalize" and cmd is None:continue
        if not isinstance(cmd,list) or not cmd or not all(isinstance(arg,str) for arg in cmd):
            raise ValueError(f"{key} must be an argv array")
    if cfg.get("max_attempts",1)<1 or cfg.get("limit_seconds",600)<=0 or cfg.get("grace_ms",0)<0:
        raise ValueError("Invalid attempt or comparison limit")
    if any(cfg.get(k,d)<=0 for k,d in [("interval_seconds",.5),("startup_timeout",90),("observer_timeout",15),("finalize_timeout",60)]):
        raise ValueError("Timeouts and polling interval must be positive")
    return cfg


def references(root,cfg):
    paths=[]
    for receipt in root.glob("attempt-*/result.json"):
        info=json.loads(receipt.read_text())
        if info["course"]!=cfg["course"] or info["route"]!=cfg["route"]:
            continue
        path=receipt.parent/"observed-run.jsonl"
        if info.get("status") in ("finished","reset") and path.is_file():
            paths.append(path)
    return paths


def imported_evidence(dirs,cfg):
    paths=[]
    for folder in dirs:
        archive=Path(folder)/"observed-run.jsonl.gz"
        native=json.loads((Path(folder)/"native-save.json").read_text())
        hashes=(Path(folder)/"initial-hashes.txt").read_text().splitlines()
        same_template=any(line.split(maxsplit=1)[0]==cfg["course"]["template_sha256"] and
                          line.endswith("/level.dat") for line in hashes if line.strip())
        if (native.get("world_version")!=cfg["course"]["version"] or
                native.get("noise_generator_seeds")!=cfg["course"]["seeds"] or
                native.get("commands_allowed") is not False or native.get("game_mode")!=0 or not same_template):
            raise ValueError(f"{folder}: seed, Minecraft version, survival settings or template mismatch")
        other=read_attempt(archive)
        if other is None or "enter_end" not in other.splits:
            raise ValueError(f"{folder}: route through End cannot be confirmed")
        route="nether" if "enter_nether" in other.splits else "direct"
        if route != cfg["route"]:raise ValueError(f"{folder}: different route class")
        paths.append(archive)
    return paths


def expand(argv,values):
    result=[]
    for arg in argv:
        for name,value in values.items():
            arg=arg.replace("{"+name+"}",str(value))
        result.append(arg)
    return result


def stop_group(proc):
    # The child is a new session leader. Abandoned children of its launcher still
    # have this PGID. A detached GUI cannot be managed by this adapter.
    try:os.killpg(proc.pid,signal.SIGTERM)
    except ProcessLookupError:
        proc.wait()
        return
    until=time.monotonic()+4
    while time.monotonic()<until:
        proc.poll()
        try:os.killpg(proc.pid,0)
        except ProcessLookupError:return
        time.sleep(.05)
    try:os.killpg(proc.pid,signal.SIGKILL)
    except ProcessLookupError:pass
    proc.wait()


def wait_for_result(cfg,proc,log,refs):
    started=time.monotonic()
    seen_at=started
    previous_stamp=None
    best=None
    while True:
        now=time.monotonic()
        run=read_attempt(log,live=True) if log.is_file() else None
        if run is not None:
            if best is None:best=best_times(refs,run.spawn)
            if run.last!=previous_stamp:seen_at,previous_stamp=now,run.last
            state,why=classify(run,best,cfg.get("gates",[]),cfg.get("grace_ms",0),
                               cfg.get("limit_seconds",600),round(time.time()*1000))
            if "enter_nether" in run.splits and cfg["route"]=="direct":
                state,why="error","route_mismatch"
            if "enter_end" in run.splits and cfg["route"]=="nether" and "enter_nether" not in run.splits:
                state,why="error","route_mismatch"
            if state=="finished" and not {"enter_end","dragon","finish"}<=run.splits.keys():
                state,why="error","unverified_finish"
            if state!="running":return state,why,run,best
            if now-seen_at>cfg.get("observer_timeout",15):return "error","observer_stopped",run,best
        elif now-started>cfg.get("startup_timeout",90):
            return "error","no_spawn",None,{}
        if proc.poll() is not None:return "error","launcher_exited",run,best or {}
        time.sleep(cfg.get("interval_seconds",.5))


def run_batch(cfg,root,archives):
    root.mkdir(parents=True,exist_ok=True)
    for _ in range(cfg.get("max_attempts",1)):
        refs=archives+references(root,cfg)
        folder=Path(tempfile.mkdtemp(prefix="attempt-",dir=root)).resolve()
        game=folder/"game"
        world=game/"saves"/"Week29"
        world.mkdir(parents=True)
        shutil.copyfile(cfg["world_template"],world/"level.dat")
        if "mod" in cfg:
            (game/"mods").mkdir()
            shutil.copyfile(cfg["mod"],game/"mods"/Path(cfg["mod"]).name)
            shutil.copyfile(cfg["itinerary"],world/"survival-route.json")
            (world/"astra-week29.enabled").touch()
        log=world/"observed-run.jsonl"
        values={"attempt_dir":folder,"game_dir":game,"world_dir":world,"world_name":world.name,"log":log,
                "attempt_id":folder.name,"result":folder/"result.json"}
        result={"course":cfg["course"],"route":cfg["route"],"status":"launching","reason":None}
        json_out(values["result"],result)
        proc=None
        with (folder/"launcher.log").open("w") as output:
            try:
                proc=subprocess.Popen(expand(cfg["launch"],values),cwd=folder,start_new_session=True,
                                      stdout=output,stderr=subprocess.STDOUT,stdin=subprocess.DEVNULL)
                state,why,run,best=wait_for_result(cfg,proc,log,refs)
                result.update(status=state,reason=why,previous_best_ms=best,splits_ms=run.splits if run else {})
                json_out(values["result"],result)
                if state=="finished" and cfg.get("finalize"):
                    # User's capture/saving adapter runs while the verified game is still alive.
                    final=subprocess.Popen(expand(cfg["finalize"],values),cwd=folder,start_new_session=True,
                                           stdout=output,stderr=subprocess.STDOUT,stdin=subprocess.DEVNULL)
                    try:
                        result["finalize_exit"]=final.wait(timeout=cfg.get("finalize_timeout",60))
                    finally:stop_group(final)
                    if result["finalize_exit"]!=0:raise ValueError("Finish callback failed; run evidence is retained")
            except (OSError,ValueError,KeyError,subprocess.TimeoutExpired) as err:
                result.update(status="error",reason=str(err))
            except KeyboardInterrupt:
                result.update(status="interrupted",reason="operator_cancel")
                raise
            finally:
                if proc is not None:stop_group(proc)
                if log.is_file():
                    # The launcher can be interrupted mid-write. Preserve original bytes under game/.
                    # Only newline-terminated observation records enter the historical PB pool.
                    saved=folder/"observed-run.jsonl"
                    with log.open("rb") as source,(folder/"observer.tmp").open("wb") as safe:
                        for line in source:
                            if line.endswith(b"\n"):safe.write(line)
                    (folder/"observer.tmp").replace(saved)
                json_out(values["result"],result)
        print(json.dumps(dict(attempt=folder.name,**result),ensure_ascii=False),flush=True)
        if result["status"]=="finished":return 0
        if result["status"]!="reset":return 2
    return 10


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--config",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True)
    p.add_argument("--reference-evidence",type=Path,action="append",default=[])
    args=p.parse_args()
    cfg=load_config(args.config.resolve())
    return run_batch(cfg,args.out.resolve(),imported_evidence(args.reference_evidence,cfg))


if __name__=="__main__":
    try:raise SystemExit(main())
    except (ValueError,OSError,KeyError) as e:raise SystemExit(str(e))
