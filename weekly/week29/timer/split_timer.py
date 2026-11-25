#!/usr/bin/env python3
"""Compare Minecraft observer splits and burn an RTA clock into continuous real footage."""
import argparse
from dataclasses import dataclass, field
from fractions import Fraction
import gzip
import json
import math
from pathlib import Path
import subprocess
import tempfile
import time

NETHER = "minecraft:the_nether"
END = "minecraft:the_end"
LABELS = {"first_bed": "首张床", "enter_nether": "进下界", "enter_end": "进末地",
          "dragon": "击杀龙", "finish": "通关"}


@dataclass
class Attempt:
    spawn: int
    last: int
    splits: dict = field(default_factory=dict)
    died: bool = False
    after_death: set = field(default_factory=set)

    def mark(self, name, now):
        if name not in self.splits:
            self.splits[name] = now - self.spawn
            if self.died:
                self.after_death.add(name)


def read_attempt(path, live=False):
    read = gzip.open if path.suffix == ".gz" else open
    run = None
    tick = -1
    with read(path, "rb") as stream:
        for line in stream:
            if not line.endswith(b"\n") and live:
                break
            row = json.loads(line)
            kind, now, next_tick = row["event"], row["time_ms"], row["tick"]
            if kind == "spawn":
                if run is not None or next_tick != 0:
                    raise ValueError(f"{path}: repeated or invalid spawn")
                run = Attempt(now, now)
            if run is None or not isinstance(now, int) or now < run.last or next_tick <= tick:
                raise ValueError(f"{path}: observer tick or clock discontinuity")
            if "finish" in run.splits:
                raise ValueError(f"{path}: data follows credits")
            tick, run.last = next_tick, now
            if kind == "state":
                if row["hp"] <= 0:
                    run.died = True
                inv = row["inv"]
                if any(k.endswith("_bed") and n > 0 for k, n in inv.items()):
                    run.mark("first_bed", now)
                if row["dimension"] == NETHER:
                    run.mark("enter_nether", now)
                if row["dimension"] == END:
                    run.mark("enter_end", now)
                    if row.get("dragon_hp") == 0:
                        run.mark("dragon", now)
            elif kind == "end_credits":
                run.mark("finish", now)
            elif kind != "spawn":
                raise ValueError(f"{path}: unrecognized event {kind}")
    return run


def best_times(references, start):
    best = {}
    for path in references:
        other = read_attempt(path)
        if other is None or other.last >= start:
            raise ValueError(f"{path}: comparison must be from an earlier, separate attempt")
        for name, ms in other.splits.items():
            if name not in other.after_death:
                best[name] = min(ms, best.get(name, ms))
    return best


def classify(run, best, gates, grace, limit, now):
    elapsed = run.splits.get("finish", max(0, now - run.spawn))
    if run.died:
        return "reset", "death"
    if limit is not None and elapsed >= round(limit * 1000):
        return "reset", "time_limit"
    for name in gates:
        if name not in best:
            continue
        at = run.splits.get(name, run.last-run.spawn)
        if at > best[name] + grace:
            return "reset", name
    return ("finished", "finish") if "finish" in run.splits else ("running", "")


def watch(args):
    earliest = None
    last_printed = None
    while True:
        run = read_attempt(args.log, live=True) if args.log.is_file() else None
        if run is None:
            time.sleep(args.interval)
            continue
        if earliest is None:
            earliest = best_times(args.reference, run.spawn)
        now = round(time.time() * 1000)
        state, reason = classify(run, earliest, args.gate, args.grace_ms, args.limit, now)
        if (args.route == "direct" and "enter_nether" in run.splits or
            args.route == "nether" and "enter_end" in run.splits and "enter_nether" not in run.splits):
            raise ValueError("Observed route differs from selected comparison class")
        if state == "finished" and not {"enter_end", "dragon", "finish"} <= run.splits.keys():
            raise ValueError("Finish lacks End and dragon evidence")
        snapshot = (state, tuple(run.splits))
        if snapshot != last_printed:
            print(json.dumps(dict(status=state, reason=reason, elapsed_ms=run.splits.get("finish", now-run.spawn),
                                  splits=run.splits, previous_best_ms=earliest)), flush=True)
            last_printed = snapshot
        if state in ("finished", "reset"):
            return 0 if state == "finished" else 10
        time.sleep(args.interval)


def clock(ms, precision=2):
    ms = max(0, int(ms))
    frac = f"{ms % 1000:03}"[:precision]
    return f"{ms // 60000}:{ms // 1000 % 60:02}.{frac}"


def ass_time(frame, fps):
    cs = math.floor(frame * 100 / fps)
    return f"{cs // 360000}:{cs // 6000 % 60:02}:{cs // 100 % 60:02}.{cs % 100:02}"


def make_overlay(path, run, best, fps, offset, count):
    # The first input frame is the origin. Rendering a range never resets the RTA.
    split_order = sorted((name for name in LABELS if name in run.splits), key=run.splits.get)
    left, right, top = 718, 948, 69
    rows_y = top + 70
    bottom = rows_y + len(split_order) * 17 + 4
    primary = "&H00F6F8FA"  # R/G/B FA F8 F6 in ASS's BGR notation
    muted = "&H00B9C0C2"
    ahead = "&H00BAE8AA"
    behind = "&H0093A1FF"
    header = """[Script Info]
ScriptType: v4.00+
PlayResX: 960
PlayResY: 600
ScaledBorderAndShadow: yes
WrapStyle: 2
[V4+ Styles]
Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding
Style: Text,Noto Sans CJK SC,13,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
Style: Digits,DejaVu Sans Mono,14,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,9,0,0,0,1
[Events]
Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
"""
    data = [header]
    def event(begin, end, style, tags, text, layer=1):
        if begin < end:
            data.append(f"Dialogue: {layer},{ass_time(begin,fps)},{ass_time(end,fps)},{style},,0,0,0,,{{{tags}}}{text}\n")
    def text(x,y,value,size=13,color=primary,align=7,style="Text",a=0,b=count):
        event(a,b,style,rf"\an{align}\pos({x},{y})\fs{size}\1c{color}&",value)
    event(0,count,"Text",rf"\an7\pos(0,0)\p1\1c&H171714&\1a&H30&",
          f"m {left} {top} l {right} {top} {right} {bottom} {left} {bottom}",0)
    text(left+11,top+6,"总用时",16,muted)
    text(left+11,top+50,"分段",14,muted)
    text(right-83,top+50,"本局",14,muted,9)
    text(right-10,top+50,"历史最快",14,muted,9)
    for idx,name in enumerate(split_order):
        y=rows_y+idx*17
        text(left+11,y,LABELS[name],15)
        ref=clock(best[name]) if name in best else "—"
        text(right-10,y,ref,15,muted,9,"Digits")
        finish_frame=math.ceil(Fraction(run.splits[name],1000)*fps)
        start = max(0, min(count, finish_frame-offset))
        color = primary if name not in best else ahead if run.splits[name]<=best[name] else behind
        text(right-83,y,clock(run.splits[name]),15,color,9,"Digits",a=start)
        begin_frame = 0 if idx==0 else math.ceil(Fraction(run.splits[split_order[idx-1]],1000)*fps)
        before=max(0,min(count,begin_frame-offset))
        text(right-83,y,"—",14,muted,9,"Digits",a=0,b=before)
        # A pending split shows an actual time only once the game reports the event.
        text(right-83,y,"···",14,primary,9,"Digits",a=before,b=start)
    for i in range(count):
        ms = min(run.splits["finish"], round((i+offset)*1000/fps))
        text(right-10,top,clock(ms,3),29,primary,9,"Digits",a=i,b=i+1)
        reached=[name for name in split_order if run.splits[name] <= ms]
        segment_start = run.splits[reached[-1]] if reached else 0
        next_label = LABELS[split_order[len(reached)]] if len(reached)<len(split_order) else "通关"
        if len(reached) == len(split_order):
            segment_start = run.splits[split_order[-2]] if len(split_order) > 1 else 0
        text(left+11,top+30,"本段 · "+next_label,14,muted,a=i,b=i+1)
        text(right-10,top+29,clock(ms-segment_start),17,primary,9,"Digits",a=i,b=i+1)
    path.write_text("".join(data),encoding="utf-8")


def probe(path):
    return json.loads(subprocess.check_output(["ffprobe","-v","error","-show_streams","-show_format","-of","json",str(path)]))


def render(args):
    run=read_attempt(args.log)
    if not run or run.died or not {"enter_end","dragon","finish"}<=run.splits.keys():
        raise ValueError("Need one continuous surviving run that reaches the End, kills the dragon and reaches credits")
    if args.limit is not None and run.splits["finish"] >= round(args.limit*1000):
        raise ValueError(f"Run did not finish inside {args.limit:g} seconds")
    best=best_times(args.reference,run.spawn)
    source=probe(args.raw)
    stream=next(s for s in source["streams"] if s["codec_type"]=="video")
    fps=Fraction(stream["r_frame_rate"])
    if fps <= 0 or fps > 120 or Fraction(stream["avg_frame_rate"]) != fps:
        raise ValueError("Supply a constant-frame-rate screen capture; its actual timing must match its nominal rate")
    raw_times = subprocess.check_output(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "packet=pts_time", "-of", "csv=p=0", str(args.raw)], text=True
    ).splitlines()
    timestamps = sorted(float(value.strip().split(",")[0]) for value in raw_times if value.strip())
    if not timestamps:
        raise ValueError("No source video frames")
    resolution = float(Fraction(stream["time_base"]))
    tolerance = max(.0005, min(float(1 / fps) / 3, resolution * 1.1))
    if any(abs(t - timestamps[0] - n / float(fps)) > tolerance for n, t in enumerate(timestamps)):
        raise ValueError("Capture contains timestamp gaps; refusing to flatten real elapsed time")
    marker=int(args.startms.read_text().strip())
    if marker>run.spawn:raise ValueError("Capture started after spawn")
    recording_frames=len(timestamps)
    spawn_frame=round(Fraction(run.spawn-marker,1000)*fps)
    first_frame=round(args.from_seconds*fps)
    end_frame=math.ceil(Fraction(run.splits["finish"],1000)*fps)
    if spawn_frame+end_frame>=recording_frames:
        raise ValueError("Capture does not reach credits")
    duration_frames=min(recording_frames-spawn_frame,end_frame+round(args.tail*fps))
    if args.length is not None:duration_frames=min(duration_frames,first_frame+round(args.length*fps))
    count=duration_frames-first_frame
    if count<=0:raise ValueError("Empty render range")
    args.out.parent.mkdir(parents=True,exist_ok=True)
    if args.out.exists():raise ValueError("Output already exists")
    with tempfile.TemporaryDirectory(dir=args.out.parent,prefix=".split-timer-") as scratch:
        temp=Path(scratch)
        make_overlay(temp/"clock.ass",run,best,fps,first_frame,count)
        output=temp/"out.mp4"
        cmd=["ffmpeg","-hide_banner","-nostdin","-loglevel","error","-filter_threads","1","-ss",f"{float((spawn_frame+first_frame)/fps):.9f}",
             "-i",str(args.raw.resolve())]
        length=float(count/fps)
        if args.music:
            if args.repeat_music:cmd+=["-stream_loop","-1"]
            cmd+=["-ss",str(args.from_seconds),"-i",str(args.music.resolve())]
        cmd+=["-map","0:v:0","-vf",f"settb={fps.denominator}/{fps.numerator},setpts=N,ass=clock.ass","-t",f"{length:.9f}","-r",str(fps),"-fps_mode","cfr","-video_track_timescale",str(fps.numerator*1000),
              "-c:v","libx264","-threads",str(args.threads),"-preset","veryfast","-crf","19","-pix_fmt","yuv420p"]
        if args.music:
            if any(s["codec_type"]=="audio" for s in source["streams"]):
                cmd+=["-filter_complex","[0:a:0][1:a:0]amix=inputs=2:duration=longest:normalize=0,alimiter=limit=0.92[a]","-map","[a]"]
            else:cmd+=["-map","1:a:0","-af","apad"]
            cmd+=["-c:a","aac","-b:a","192k"]
        elif any(s["codec_type"]=="audio" for s in source["streams"]):
            cmd+=["-map","0:a:0","-c:a","aac","-b:a","192k"]
        else:cmd+=["-an"]
        subprocess.run(cmd+["-movflags","+faststart",str(output)],cwd=temp,check=True)
        output.rename(args.out)
    print(json.dumps(dict(file=str(args.out),duration_seconds=length,total_elapsed_ms=run.splits["finish"],splits=run.splits,previous_best_ms=best)))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    subs=p.add_subparsers(dest="command",required=True)
    for name in ("render","watch"):
        s=subs.add_parser(name)
        s.add_argument("--log",type=Path,required=True)
        s.add_argument("--reference",type=Path,action="append",default=[],help="Earlier real observer trace from the same course; repeat to pool observed split bests")
        s.add_argument("--limit",type=float,help="Strict finish limit, seconds (equality is a miss)")
        if name=="watch":
            s.add_argument("--route",choices=("nether","direct"),required=True)
            s.add_argument("--gate",choices=LABELS,action="append",default=[])
            s.add_argument("--grace-ms",type=int,default=0)
            s.add_argument("--interval",type=float,default=.5)
        else:
            s.add_argument("--raw",type=Path,required=True)
            s.add_argument("--startms",type=Path,required=True)
            s.add_argument("--out",type=Path,required=True)
            s.add_argument("--music",type=Path)
            s.add_argument("--repeat-music",action="store_true")
            s.add_argument("--from-seconds",type=float,default=0)
            s.add_argument("--length",type=float)
            s.add_argument("--tail",type=float,default=5)
            s.add_argument("--threads",type=int,default=3)
    a=p.parse_args()
    if a.limit is not None and a.limit<=0:p.error("limit must be positive")
    if a.command=="watch":
        if a.route=="direct" and "enter_nether" in a.gate:p.error("Direct route has no Nether split")
        if a.grace_ms<0 or a.interval<=0:p.error("Invalid grace or interval")
        return watch(a)
    if a.from_seconds<0 or a.tail<0 or a.threads<1 or (a.length is not None and a.length<=0):p.error("Invalid render range or threads")
    if a.repeat_music and not a.music:p.error("Repeated playback requires an audio file")
    render(a)


if __name__=="__main__":
    try:
        raise SystemExit(main())
    except (OSError,ValueError,KeyError,subprocess.CalledProcessError) as err:
        raise SystemExit(str(err))
