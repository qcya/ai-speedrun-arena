#!/usr/bin/env python3
"""Add a 1x native spectator path and recorded first-person UI to a separate MCPR."""
import argparse
import bisect
import json
from pathlib import Path
import re
import shutil
import struct
import uuid
import zipfile

PRESET = "Astra · 第一人称 1×"
OVERLAY = "astra/film-overlay.json"


def varint(data, pos=0):
    value=0
    for i in range(5):
        n=data[pos];pos+=1;value|=(n&127)<<(7*i)
        if n<128:return value,pos
    raise ValueError("Incomplete VarInt in recording")


def literal_chat(component):
    if isinstance(component,str):return component
    if isinstance(component,list):return ''.join(literal_chat(c) for c in component)
    if not isinstance(component,dict):return ''
    return str(component.get('text',''))+literal_chat(component.get('extra',[]))


def distance_packet(packet, offset):
    """Read only the vanilla debug message, never player chat or an action bar."""
    size, pos=varint(packet,offset)
    end=pos+size
    if end+17!=len(packet) or packet[end] not in (0,1) or packet[end+1:]!=bytes(16):
        return None
    component=json.loads(packet[pos:end].decode('utf-8'))
    # Some packet recorders serialize the already localized text instead of its keys.
    literal=re.fullmatch(r'(?:\[调试\]： 渲染距离：|\[Debug\]: Render Distance: )([0-9]{1,2})',
                         literal_chat(component))
    if literal and 2<=int(literal[1])<=32:return int(literal[1]),component
    if isinstance(component,dict):parts=component.get('extra',[])
    elif isinstance(component,list):parts=component
    else:return None
    if not isinstance(parts,list):return None
    prefix=any(isinstance(v,dict) and v.get('translate')=='debug.prefix' for v in parts)
    if not prefix:return None
    for part in parts:
        if isinstance(part,dict) and part.get('translate')=='debug.cycle_renderdistance.message':
            args=part.get('with')
            if not isinstance(args,list) or len(args)!=1:return None
            value=args[0]
            if isinstance(value,dict):value=value.get('text')
            if type(value) not in (str,int) or not str(value).isascii() or not str(value).isdecimal():return None
            distance=int(value)
            if 2<=distance<=32:return distance,component
    return None


def recorded_packets(archive, player):
    identities={}
    changes=[]
    feedback=[]
    with archive.open("recording.tmcpr") as data:
        while header:=data.read(8):
            if len(header)!=8:raise ValueError("Truncated MCPR packet header")
            ts,n=struct.unpack(">ii",header)
            if not 0<n<=64000000:raise ValueError("Invalid MCPR packet size")
            packet=data.read(n)
            if len(packet)!=n:raise ValueError("Truncated MCPR packet")
            pid,off=varint(packet)
            # Protocol 736: login Success and Play Spawn Player supply the authentic owner and entity.
            if pid==2 and 18<=n<=36:
                try:
                    name_len,idx=varint(packet,off+16)
                    if idx+name_len==len(packet):
                        name=packet[idx:].decode("ascii")
                        identities[packet[off:off+16]]=name
                except (IndexError,UnicodeDecodeError):pass
            elif pid==4 and 44<=n<=50:
                eid,idx=varint(packet,off);identifier=packet[idx:idx+16]
                if identities.get(identifier)==player and (not changes or changes[-1][0]!=eid):
                    changes.append((eid,ts,str(uuid.UUID(bytes=identifier))))
            elif pid==14 and identities:
                message=distance_packet(packet,off)
                if message:feedback.append((ts,*message))
    if not changes: raise ValueError(f"Recorded spawn packet for {player!r} was not found")
    if any(row[2]!=changes[0][2] for row in changes): raise ValueError("Multiple UUIDs used the same player name")
    return changes,feedback


def distance_feedback(rows,packets,start):
    """Prefer original packet text; replay only proven local keys lacking a packet."""
    # Vanilla ages chat in client ticks. In a cold world, 200 observed ticks can
    # span appreciably more than ten seconds of the continuous real-time movie.
    ticks=[(r['elapsed_ms'],r['run_tick']) for r in rows if type(r.get('run_tick')) is int]
    tick_times=[t for t,_ in ticks]
    def tick_at(ms):
        index=bisect.bisect_right(tick_times,ms)-1
        return ticks[index][1] if index>=0 else ms//50
    native=[{'elapsed_ms':ts-start,'chat_tick':tick_at(ts-start),'after':value,'source':'packet','component':text}
            for ts,value,text in packets]
    local=[]
    steps={'F3+F':1,'Shift+F3+F':-1}
    for row in rows:
        if row.get('event')!='render_distance_shortcut':continue
        before,after=row.get('before'),row.get('after')
        step=steps.get(row.get('shortcut'))
        if (type(before) is not int or type(after) is not int or not 2<=before<=32
                or not 2<=after<=32 or step!=after-before or step is None):
            raise ValueError('Invalid recorded F3 render-distance shortcut')
        # Exact 1.16.1 Keyboard.debugWarn text tree. The native client supplies the locale.
        line={'text':'','extra':[{'translate':'debug.prefix','bold':True,'color':'yellow'},
                                 ' ',{'translate':'debug.cycle_renderdistance.message','with':[str(after)]}]}
        local.append({'elapsed_ms':row['elapsed_ms'],'chat_tick':row.get('run_tick',tick_at(row['elapsed_ms'])),
                      'after':after,'source':'keyboard','component':line})
    # Match the closest clocks first, including when genuine repeated keys hit the same value.
    pairs=sorted((abs(p['elapsed_ms']-k['elapsed_ms']),i,j)
                 for i,p in enumerate(native) for j,k in enumerate(local)
                 if p['after']==k['after'] and abs(p['elapsed_ms']-k['elapsed_ms'])<=250)
    used_packets=set()
    used_keys=set()
    for _,i,j in pairs:
        if i not in used_packets and j not in used_keys:
            used_packets.add(i)
            used_keys.add(j)
    feedback=native+[msg for j,msg in enumerate(local) if j not in used_keys]
    return sorted(feedback,key=lambda item:item['elapsed_ms'])


def timeline(changes, poses, run_at_replay_ms, offset, length):
    pose_times=[row["elapsed_ms"] for row in poses]
    def prop(time,entity):
        r=poses[max(0,bisect.bisect_right(pose_times,offset+time-run_at_replay_ms)-1)]
        return {"spectate":entity,"camera:position":[r['x'],r['y']+r.get('eye_height',1.62),r['z']],
                "camera:rotation":[r['yaw'],r['pitch'],0]}
    def track(a,b,properties):
        return {"keyframes":[{"time":0,"properties":a},{"time":length,"properties":b}],
                "segments":[0],"interpolators":[{"type":"linear","properties":properties}]}
    # An entity ID can change on transfer to another dimension. Hold the previous ID
    # until the recorded spawn packet, then switch to the new original player ID.
    entity=changes[0][0]
    future=[]
    for next_entity,spawn,_ in changes:
        if spawn<=offset: entity=next_entity
        elif spawn<offset+length: future.append((spawn-offset,next_entity))
    keys={0:prop(0,entity)}
    for time,next_entity in future:
        if time>1: keys[time-1]=prop(time-1,entity)
        entity=next_entity
        keys[time]=prop(time,entity)
    keys[length]=prop(length,entity)
    camera={"keyframes":[{"time":time,"properties":value} for time,value in sorted(keys.items())],
            "segments":[0]*(len(keys)-1),"interpolators":[{"type":"linear","properties":["spectate"]}]}
    return [track({"timestamp":offset},{"timestamp":offset+length},["timestamp"]),camera]


def build(source,journal,destination,player,start,from_ms,length,*,hud=None,credits_tail_ms=0):
    rows=[json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines() if line.strip()]
    spawns=[r for r in rows if r.get("event")=="spawn"]
    if len(spawns)!=1 or not rows or rows[0]!=spawns[0]:
        raise ValueError("Expected one original spawn in this recorded journal")
    times=[r['elapsed_ms'] for r in rows]
    if times[0]!=0 or any(t<0 or b<t for t,b in zip(times,times[1:])):
        raise ValueError("Non-monotone original run clock")
    if destination.exists():raise FileExistsError("Choose a new output file; source replays are preserved")
    if from_ms<0 or from_ms>=times[-1]:raise ValueError("Start outside observed gameplay")
    end=times[-1] if length is None else from_ms+length
    if end<=from_ms or end>times[-1]:raise ValueError("Segment must be covered by observations")
    with zipfile.ZipFile(source) as src:
        meta=json.loads(src.read("metaData.json"))
        if meta.get("mcversion")!="1.16.1" or meta.get("protocol")!=736:
            raise ValueError("Expected a Java Edition 1.16.1 / protocol 736 ReplayMod archive")
        changes,chat=recorded_packets(src,player)
        eid,spawn,uid=changes[0]
        if start is None:
            if 'epoch_ms' not in spawns[0]:raise ValueError("This older journal requires a measured --spawn-replay-ms")
            start=int(spawns[0]['epoch_ms'])-int(meta['date'])
        if length is None and rows[-1].get('event')!='credits':
            # Native disconnect and the final observer tick can differ; a practice
            # excerpt must end where both packet and observer clocks still exist.
            end=min(end,int(meta['duration'])-start)
        if start<0 or end<=from_ms or start+end>int(meta['duration'])+50:
            raise ValueError("Original journal times fall outside the replay's measured coverage")
        offset=start+from_ms
        if offset+100<spawn:
            raise ValueError("The source-player entity is not present at the requested playback start")
        if credits_tail_ms < 0: raise ValueError("A credits tail must be nonnegative")
        if credits_tail_ms:
            if rows[-1].get('event')!='credits' or end!=times[-1]:
                raise ValueError("A credits tail requires an actual observed finish")
            end+=min(credits_tail_ms,max(0,int(meta['duration'])-start-end))
        poses=[r for r in rows if 'x' in r and r.get('event')!='render_distance_shortcut']
        paths=json.loads(src.read("timelines.json")) if 'timelines.json' in src.namelist() else {}
        paths[PRESET]=timeline(changes,poses,start,offset,end-from_ms)
        render={"schema":1,"source_player":player,"source_uuid":uid,"run_at_replay_ms":start,
                "from_ms":from_ms,"to_ms":end,"rows":rows}
        feedback=distance_feedback(rows,chat,start)
        if feedback:render['render_distance_feedback']=feedback
        if hud is not None:
            if hud.get('route') not in ('nether','direct'): raise ValueError('Unknown HUD route')
            prior=hud.get('previous_best_ms',{})
            if any(k not in ('nether','end','dragon','finish') or type(v) is not int or v<0 for k,v in prior.items()):
                raise ValueError('Invalid recorded personal bests')
            current=hud.get('current_splits_ms',{})
            if any(k not in ('nether','end','dragon','finish') or type(v) is not int or v<0 for k,v in current.items()):
                raise ValueError('Invalid recorded splits')
            render.update(route=hud['route'],previous_best_ms=prior,current_splits_ms=current)
        destination.parent.mkdir(parents=True,exist_ok=True)
        try:
            with zipfile.ZipFile(destination,"x",zipfile.ZIP_DEFLATED) as out:
                for entry in src.infolist():
                    if entry.filename in (OVERLAY,"timelines.json"):continue
                    with src.open(entry) as a,out.open(entry,"w") as b:shutil.copyfileobj(a,b,1<<20)
                out.writestr("timelines.json",json.dumps(paths,ensure_ascii=False,separators=(",",":")))
                out.writestr(OVERLAY,json.dumps(render,ensure_ascii=False,separators=(",",":")))
        except Exception:
            destination.unlink(missing_ok=True)
            raise
    return {"file":str(destination),"preset":PRESET,"entity":eid,"entity_spawn_ms":spawn,
            "video_duration_ms":end-from_ms,"replay_start_ms":offset,"original_ui_states":
            sum("gui_slots" in r for r in rows),"recorded_source_rows":len(rows),
            "distance_feedback_packets":len(chat),"distance_feedback_local":sum(f['source']=='keyboard' for f in feedback)}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ["source","journal","output"]:p.add_argument("--"+key,type=Path,required=True)
    p.add_argument("--player",default="Astra")
    p.add_argument("--spawn-replay-ms",type=int,help="Measured replay time of spawn for older pre-epoch journals")
    p.add_argument("--from-ms",type=int,default=0)
    p.add_argument("--length-ms",type=int)
    p.add_argument("--hud",type=Path,help="Original course-matched HUD snapshot JSON")
    p.add_argument("--credits-tail-ms",type=int,default=0,help="Native credits time to include, capped by original replay duration")
    p.add_argument("--export-instance",type=Path,help="Opt in a dedicated, closed ReplayMod instance for a one-shot native export")
    p.add_argument("--mp4",type=Path,help="New local 1920x1080 60 fps movie")
    a=p.parse_args()
    if bool(a.export_instance)!=bool(a.mp4):p.error("--export-instance and --mp4 go together")
    if a.mp4 and a.mp4.exists():p.error("Choose a new MP4 path")
    try:print(json.dumps(build(a.source,a.journal,a.output,a.player,a.spawn_replay_ms,a.from_ms,a.length_ms,
                               hud=json.loads(a.hud.read_text(encoding="utf-8")) if a.hud else None,
                               credits_tail_ms=a.credits_tail_ms),ensure_ascii=False,indent=2))
    except (OSError,ValueError,KeyError,IndexError,zipfile.BadZipFile) as e:p.exit(1,f"{e}\n")
    if a.export_instance:
        d=a.export_instance/"config";d.mkdir(parents=True,exist_ok=True)
        job={"input":str(a.output.resolve()),"output":str(a.mp4.resolve())}
        ffmpeg=shutil.which('ffmpeg')
        if ffmpeg:job['ffmpeg']=str(Path(ffmpeg).resolve())
        with (d/"astra-render-once.json").open('x',encoding='utf-8') as out:
            json.dump(job,out,indent=2)
            out.write('\n')


if __name__=="__main__":
    main()
