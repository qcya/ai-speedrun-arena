#!/usr/bin/env python3
"""Pack one native 1.16.1 self recording and its observer timeline for offline rendering."""
import argparse
import gzip
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
import zipfile
from compare_splits import TIME_LIMIT_MS, finish_fits_film, under_time_limit
from world_evidence import verify

COURSE = Path(__file__).resolve().parents[1] / "course.json"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def mod_versions(world):
    result = {}
    for archive in sorted((world.parents[1] / "mods").glob("*.jar")):
        with zipfile.ZipFile(archive) as jar:
            if "fabric.mod.json" not in jar.namelist():
                continue
            definition = json.loads(jar.read("fabric.mod.json"))
        digest = hashlib.sha256()
        with archive.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        result[definition["id"]] = {"version": definition["version"], "sha256": digest.hexdigest()}
    return result


def pack(world, replay, destination, segment):
    course = json.loads(COURSE.read_text(encoding="utf-8"))
    native = verify(world,course)
    log = (world / "astra-run.jsonl").read_bytes()
    rows = [json.loads(line) for line in log.splitlines()]
    if not rows or rows[0]["event"] != "spawn" or not (world / "astra-113.local").is_file():
        raise ValueError("Use the observed fresh Week 113 attempt")
    if any(r["run_tick"] < l["run_tick"] or r["elapsed_ms"] < l["elapsed_ms"] for l,r in zip(rows,rows[1:])):
        raise ValueError("Discontinuous observer timeline")
    receipt_path = world / "speedrunigt/record.json"
    timing = json.loads(receipt_path.read_text(encoding="utf-8"))
    if timing.get("mc_version") != "1.16.1" or timing.get("default_gamemode") != 0 or timing.get("is_cheat_allowed") is not False:
        raise ValueError("Missing native survival settings")
    with zipfile.ZipFile(replay) as capture:
        if capture.testzip():
            raise ValueError("Replay failed ZIP CRC checks")
        meta = json.loads(capture.read("metaData.json"))
        if meta.get("protocol") != 736 or not meta.get("singleplayer") or "113" not in meta.get("serverName",""):
            raise ValueError("Expected a native, singleplayer Week 113 recording")
        with capture.open("recording.tmcpr") as stream:
            lo, hi, count = None, -1, 0
            while True:
                header = stream.read(8)
                if not header: break
                if len(header)!=8: raise ValueError("Truncated packet header")
                ms,size = struct.unpack(">II",header)
                if size<1 or size>16_777_216 or ms<hi or len(stream.read(size))!=size:
                    raise ValueError("Corrupt or incomplete packet stream")
                if lo is None: lo = ms
                hi=ms;count+=1
        if count<2 or hi>meta["duration"]+1000:
            raise ValueError("Unexpected replay clock")
    if "epoch_ms" in rows[0]:
        delta=rows[0]["epoch_ms"]-meta["date"]
        if not -1000<=delta<=15000: raise ValueError("Replay and observer have mismatched starts")
    else:
        # Old calibration pilots did not record a wall-clock-to-replay alignment.
        # Their filenames alone are insufficient to establish exact GUI timing.
        delta=None
    last=rows[-1]
    complete = last["event"]=="credits" and timing.get("is_completed") is True and native["seen_credits"] and not any(r["event"]=="death" for r in rows)
    if complete and delta is None: raise ValueError("A scored run requires a recorded clock alignment")
    if delta is not None and complete and hi<delta+last["elapsed_ms"]-150:
        raise ValueError("Replay ends before the native finish")
    native_igt = timing.get("final_igt", 0)
    if not segment and (not complete or not finish_fits_film(last["elapsed_ms"]) or not under_time_limit(native_igt)):
        raise ValueError("The complete original run and uncut film through credits must fit under seven minutes")
    checks={"kind":"practice_segment" if segment else "local_full_run","course":113,
            "timing":"Local uncut RTA and native SpeedRunIGT",
            "native_completed":complete,"last_observed_ms":last["elapsed_ms"],"last_reference_tick":last["reference_tick"],
            "benchmark":{"time_ms":TIME_LIMIT_MS,"comparison":"<"},
            "native_speedrunigt_ms":native_igt,"native_world":native,"replay_date_ms":meta["date"],"spawn_minus_replay_start_ms":delta,
            "packet_count":count,"first_packet_ms":lo,"last_packet_ms":hi,"replay_duration_ms":meta["duration"],
            "native_replay_sha256":sha(replay.read_bytes()),"observer_source_sha256":sha(log)}
    if complete and not segment:
        from world_evidence import replay_pearl_ordinal, verify_route
        checks["native_route"]=verify_route(world,rows)
        stasis = checks["native_route"]["stasis_shortcuts"]
        stasis.update(replay_pearl_ordinal(replay, rows, stasis))
        checks["lead_ms"]=TIME_LIMIT_MS-native_igt
    if not segment:
        checks["recording_mods"] = mod_versions(world)
    files={"Astra.mcpr":replay.read_bytes(),"observer.jsonl.gz":gzip.compress(log,mtime=0),
           "course.json":(json.dumps(course,ensure_ascii=False,indent=2)+"\n").encode("utf-8"),"native-timer.json":receipt_path.read_bytes(),
           "take.json":(json.dumps(checks,indent=2)+"\n").encode()}
    if (world/"astra-film-attempt.txt").is_file() or not segment:
        helper=Path(__file__).resolve().parents[3]/"presentation/receipts.py"
        spec=importlib.util.spec_from_file_location("astra_receipts",helper)
        receipts=importlib.util.module_from_spec(spec);spec.loader.exec_module(receipts)
        files["render-assets/splits.json"]=json.dumps(receipts.film_data(world),indent=2).encode()
    skin_pack=world.parents[1]/"resourcepacks"/"Astra-local-skin.zip"
    if not segment:
        if not skin_pack.is_file(): raise ValueError("The recorded skin pack is required for the published full movie")
        files["render-assets/Astra-local-skin.zip"]=skin_pack.read_bytes()
        profile=world.parents[1]/"config"/"astra-film.json"
        profile_data=json.loads(profile.read_text(encoding="utf-8"))
        # Publish portable render fields only, not workstation paths such as history_dir.
        files["render-assets/astra-film.json"]=json.dumps({k:v for k,v in profile_data.items() if k in ("route","rules","model","skin_model","enabled")}).encode()
    files["SHA256SUMS"]="".join(f"{sha(value)}  {key}\n" for key,value in files.items()).encode()
    destination.parent.mkdir(parents=True,exist_ok=True)
    if destination.exists():raise FileExistsError(destination)
    with tempfile.NamedTemporaryFile(dir=destination.parent,suffix=".tmp",delete=False) as f:temp=Path(f.name)
    try:
        with zipfile.ZipFile(temp,"w") as out:
            for name, data in files.items():
                # Both packet replay and observer are already compressed.
                out.writestr(name,data,compress_type=zipfile.ZIP_STORED if name.endswith((".mcpr",".gz")) else zipfile.ZIP_DEFLATED)
        if os.name == 'nt':
            # A same-volume Windows rename is atomic and refuses an existing destination.
            # It also works on removable volumes that do not support hard links.
            os.rename(temp,destination)
        else:
            os.link(temp,destination)
    finally:temp.unlink(missing_ok=True)
    return checks


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world",type=Path);parser.add_argument("replay",type=Path);parser.add_argument("out",type=Path)
    parser.add_argument("--segment",action="store_true",help="Explicitly label a partial training recording")
    options=parser.parse_args()
    try:print(json.dumps(pack(options.world,options.replay,options.out,options.segment),indent=2))
    except (OSError,ValueError,KeyError,zipfile.BadZipFile) as exc:parser.exit(1,f"{exc}\n")
