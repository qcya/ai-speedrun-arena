#!/usr/bin/env python3
"""Record a native 1920x1080 Minecraft X11 window and audit real HUD updates."""
import argparse
import csv
import json
import os
from pathlib import Path
import re
import subprocess
import time

WIDTH, HEIGHT, FPS = 1920, 1080, 60


def window_geometry(window):
    output = subprocess.check_output(["xwininfo","-id",window],text=True)
    fields = {}
    for key in ("Width", "Height", "Absolute upper-left X", "Absolute upper-left Y"):
        match=re.search(r"^\s*"+re.escape(key)+r":\s*(-?\d+)",output,re.M)
        if not match:
            raise ValueError("Could not read X11 client geometry")
        fields[key]=int(match[1])
    if (fields["Width"],fields["Height"]) != (WIDTH,HEIGHT):
        raise ValueError("The Minecraft client area must be exactly 1920x1080 before recording")
    return fields["Absolute upper-left X"], fields["Absolute upper-left Y"]


def record(a):
    x,y=window_geometry(a.window)
    if x<0 or y<0:
        raise ValueError("The entire client area must be visible")
    out=a.output.resolve()
    out.parent.mkdir(parents=True,exist_ok=True)
    qa=out.with_suffix(".hud-framehash")
    take=out.with_suffix(".capture.json")
    if any(p.exists() for p in (out,qa,take)):
        raise FileExistsError("Choose an unused recording name")
    display=os.environ["DISPLAY"].split(".0+",1)[0]
    command=["ffmpeg","-nostdin","-hide_banner","-loglevel","warning",
             "-filter_complex_threads","2","-thread_queue_size","64","-f","x11grab",
             "-framerate",str(FPS),"-draw_mouse","1","-video_size",f"{WIDTH}x{HEIGHT}",
             "-i",f"{display}+{x},{y}","-filter_complex",
             "[0:v]split=2[main][check];[check]crop=220:20:1700:0,format=gray[clock]",
             "-map","[main]","-an","-c:v","libx264","-threads",str(a.threads),"-preset","ultrafast",
             "-crf","18","-pix_fmt","yuv420p","-r",str(FPS),"-fps_mode","cfr"]
    if a.seconds:command+=["-t",str(a.seconds)]
    command += [str(out),"-map","[clock]","-an","-c:v","rawvideo","-threads","1",
                "-fps_mode","passthrough","-f","framehash"]
    if a.seconds:command+=["-t",str(a.seconds)]
    command.append(str(qa))
    started=time.time_ns()//1000000
    try:
        subprocess.run(command,check=True)
    except KeyboardInterrupt:
        pass
    except subprocess.CalledProcessError as e:
        if e.returncode not in (255,-2):raise
    if not out.exists() or not qa.exists():raise ValueError("Recording was not finalized")
    hashes=[]
    for row in csv.reader(qa.read_text().splitlines()):
        if row and not row[0].startswith("#"):
            hashes.append(row[-1].strip())
    changes=sum(a!=b for a,b in zip(hashes,hashes[1:]))
    duration=float(subprocess.check_output(["ffprobe","-v","error","-show_entries","format=duration",
                            "-of","default=nw=1:nk=1",str(out)],text=True).strip())
    receipt={"width":WIDTH,"height":HEIGHT,"sample_hz":FPS,"capture_start_before_ffmpeg_ms":started,
             "duration_seconds":duration,"input_samples":len(hashes),"changed_clock_samples":changes,
             "different_clock_samples_per_second":round(changes/duration,2) if duration else 0,
             "audit_region":"Original pixels of live total clock. Use during gameplay; credits freeze intentionally."}
    take.write_text(json.dumps(receipt,indent=2)+"\n")
    print(json.dumps(receipt,indent=2))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--window",required=True,help="Minecraft X11 window ID from xwininfo -root -tree")
    p.add_argument("--output",required=True,type=Path)
    p.add_argument("--seconds",type=float)
    p.add_argument("--threads",type=int,default=3)
    a=p.parse_args()
    if (a.seconds is not None and a.seconds<=0) or a.threads<1:p.error("Invalid duration or thread count")
    try:record(a)
    except (OSError,ValueError,subprocess.CalledProcessError) as e:p.exit(1,f"{e}\n")


if __name__=="__main__":
    main()
