#!/usr/bin/env python3
"""Run fresh local Week 113 attempts using Prism. Frozen playback stays in render.py."""
import argparse
import contextlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile

import render

ROOT = Path(__file__).resolve().parent
WEEK = ROOT / 'weekly/week113'
WORLD = 'Astra-W113'
RULES = 'week113-6.0.12-flags207'
sys.path.insert(0, str(WEEK / 'tools'))


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write(path, obj):
    temp = path.with_suffix('.new')
    temp.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temp.replace(path)


class Observer:
    """Read only complete new journal lines, including when resuming the monitor."""
    def __init__(self, path):
        self.path, self.offset = path, 0
        self.events, self.last = {}, {}

    def update(self):
        if not self.path.is_file():
            return
        with self.path.open('rb') as f:
            f.seek(self.offset)
            for line in f:
                if not line.endswith(b'\n'):
                    break
                self.offset += len(line)
                self.last = json.loads(line)
                event = self.last['event']
                if event != 'state':
                    self.events.setdefault(event, self.last)


def split_cutoffs(game, world, gates):
    if not gates or not (world / 'astra-film-attempt.txt').is_file():
        return None
    config = read(game / 'config/astra-film.json')
    key = str(uuid.UUID((world / 'astra-film-attempt.txt').read_text().strip()))
    receipt = Path(config['history']) / (key + '.jsonl')
    if not receipt.is_file():
        return None
    with receipt.open(encoding='utf-8') as f:
        line = f.readline()
    if not line.endswith('\n'):
        return None
    head = json.loads(line)
    return {key: int(head['wall_ms']) + int(ms) for key, ms in head.get('previous_best_ms', {}).items()
            if key in gates and type(ms) is int and ms >= 0}


def decision(journal, cutoffs=None):
    if 'death' in journal.events:
        return 'death'
    if 'credits' in journal.events:
        return 'credits'
    row = journal.last
    if row.get('elapsed_ms', 0) >= 600000:
        return 'ten_minute_rta_limit'
    for split, clock in (cutoffs or {}).items():
        event = {'nether': 'enter_nether', 'end': 'enter_end'}[split]
        reached = journal.events.get(event, row)
        if reached.get('epoch_ms', 0) > clock:
            return 'missed_prior_' + split
    return None


def game_alive(folder, token):
    if not (folder / (token + '.ready')).is_file():
        return None
    with (folder / (token + '.lock')).open('r+b') as f:
        if os.name == 'nt':
            import msvcrt
            try:
                msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                return True
            msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            try:
                fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB, 1)
            except BlockingIOError:
                return True
            fcntl.lockf(f, fcntl.LOCK_UN, 1)
    return False


def native_replay(game, spawn_ms):
    matches = []
    for replay in (game / 'replay_recordings').rglob('*.mcpr'):
        with zipfile.ZipFile(replay) as z:
            meta = json.loads(z.read('metaData.json'))
            if meta.get('protocol') != 736 or z.getinfo('recording.tmcpr').file_size < 16:
                continue
        if meta.get('singleplayer') and -1000 <= spawn_ms - meta['date'] <= 15000:
            matches.append(replay)
    if len(matches) != 1:
        raise ValueError(f'Expected one finalized ReplayMod capture matching this spawn, found {len(matches)} in {game}')
    with zipfile.ZipFile(matches[0]) as z:
        corrupt = z.testzip()
        if corrupt:
            raise ValueError(f'Corrupt native replay member: {corrupt}')
    return matches[0]


def inspect_world(world):
    rows = Observer(world / 'astra-run.jsonl')
    rows.update()
    timer_file = world / 'speedrunigt/record.json'
    timer = read(timer_file) if timer_file.is_file() else {}
    from compare_splits import compare
    splits = compare(timer_file, WEEK / 'champion.json') if 'advancements' in timer else []
    location = {name: rows.last[name] for name in ('dim', 'x', 'y', 'z', 'hp', 'reference_tick',
                'opening_action', 'middle_action', 'end_action') if name in rows.last}
    return {'last_state': location, 'comparison_ms': splits,
            'observer_event': rows.last.get('event'), 'final_igt_ms': timer.get('final_igt'),
            'observer_rta_ms': rows.last.get('elapsed_ms'), 'native_completed': timer.get('is_completed') is True,
            'observed_credits': 'credits' in rows.events, 'death': 'death' in rows.events,
            'spawn_epoch_ms': rows.events.get('spawn', {}).get('epoch_ms')}


def watch(game, world, attempt, statefile, gates, launcher=None):
    folder = game / 'astra-practice'
    token = attempt['token']
    close = folder / (token + '.close')
    rows = Observer(world / 'astra-run.jsonl')
    started = time.monotonic()
    last_status, last_bytes, last_activity = -1, -1, started
    cutoffs = None
    while True:
        rows.update()
        now = time.monotonic()
        if rows.offset != last_bytes:
            last_bytes, last_activity = rows.offset, now
        if cutoffs is None:
            cutoffs = split_cutoffs(game, world, gates)
        outcome = attempt.get('reason') or decision(rows, cutoffs)
        if not outcome and rows.last and now - last_activity > 180:
            outcome = 'observer_stalled'
        alive = game_alive(folder, token)
        if alive is False:
            attempt['reason'] = outcome or 'client_exited'
            write(statefile, attempt)
            return
        if launcher is not None and launcher.poll() not in (None, 0) and alive is None:
            raise RuntimeError('Prism could not start this isolated instance; check the launcher and its account.')
        if not rows.last and now - started > 600 and not outcome:
            if alive is None:
                raise RuntimeError('No Minecraft supervisor started in ten minutes. Check Prism, its Java installation and the account. The instance is preserved.')
            outcome = 'world_start_timeout'
        if outcome and 'reason' not in attempt:
            attempt['reason'] = outcome
            write(statefile, attempt)
        if outcome:
            # Give the original credits screen 18 seconds of real time before normal game close.
            finish_wall = (rows.events.get('credits', {}).get('epoch_ms', 0) / 1000) + 18
            if outcome != 'credits' or time.time() >= finish_wall:
                if not close.exists():
                    close.touch()
                if time.time() - close.stat().st_mtime > 180:
                    raise RuntimeError('Minecraft has not completed normal shutdown. The attempt is preserved for resumption.')
        bucket = int(rows.last.get('elapsed_ms', 0) // 15000)
        if rows.last and bucket != last_status:
            print(f"  {rows.last.get('elapsed_ms',0)/1000:.1f}s  {rows.last.get('dim','')}  {rows.last.get('middle_action','')}", flush=True)
            last_status = bucket
        time.sleep(.35)


def select_skin(explicit, work):
    if explicit:
        path = explicit.expanduser().resolve()
        if not path.is_file():
            raise FileNotFoundError(path)
        return path
    for folder in [work, ROOT / 'render-assets', Path.cwd(), Path.home() / 'Downloads', Path.home() / 'Desktop', Path.home() / 'Pictures']:
        path = folder / 'qcya_ovo.png'
        if path.is_file():
            return path
    # A qualified bundle may already contain the approved integral skin asset.
    if render.DEFAULT_TAKE.is_file():
        render.verify_take(render.DEFAULT_TAKE, False)
        import io
        with zipfile.ZipFile(render.DEFAULT_TAKE) as outer:
            with zipfile.ZipFile(io.BytesIO(outer.read('render-assets/Astra-local-skin.zip'))) as pack:
                dest = work / 'bundle-skin.png'
                dest.write_bytes(pack.read('assets/astra_film/textures/local_skin.png'))
                return dest
    raise ValueError('Point --skin at the original local qcya_ovo.png, or put it in Downloads.')


def prepare_attempt(prism_data, work, skin, deps, opening="fast"):
    token = str(uuid.uuid4())
    instance = Path(tempfile.mkdtemp(prefix='Astra-Practice-', dir=prism_data / 'instances'))
    game = instance / '.minecraft'
    game.mkdir()
    (instance / 'mmc-pack.json').write_text(json.dumps({'formatVersion': 1, 'components': [
        {'uid':'net.minecraft','version':'1.16.1'}, {'uid':'net.fabricmc.fabric-loader','version':'0.16.14'}]}))
    (instance / 'instance.cfg').write_text('[General]\nInstanceType=OneSix\nname=Astra Week 113 practice\n')
    from types import SimpleNamespace
    setup = render.load_helper('prepare_weekly', WEEK / 'prepare.py')
    setup.prepare(SimpleNamespace(game_dir=game, world_name=WORLD, skin=None, ranked_jar=deps['ranked'],
                                  replay_jar=deps['replaymod'], autostart=True, shield=opening=='shield'))
    film = render.load_helper('prepare_local_film', ROOT / 'presentation/prepare.py')
    film.prepare(game, skin, 'auto', 'nether', RULES, ROOT / 'presentation/astra-local-film.jar', work / 'split-history')
    config = game / 'config/replaymod.json'
    write(config, {'recording': {'recordSingleplayer':True,'autoStartRecording':True,'renameDialog':False,'indicator':False}})
    switch = game / 'astra-practice'
    switch.mkdir()
    (switch / 'enabled.txt').write_text(token, encoding='utf-8')
    return {'token': token, 'instance': str(instance), 'opening': opening, 'created_epoch_ms': round(time.time()*1000)}


def finish_attempt(attempt, work, destination):
    game = Path(attempt['instance']) / '.minecraft'
    world = game / 'saves' / WORLD
    results = inspect_world(world)
    receipt = dict(attempt, **results)
    verdict = attempt['reason']
    folder = work / 'receipts'
    folder.mkdir(exist_ok=True)
    record = folder / (attempt['token'] + '.json')
    replay = None
    if results['spawn_epoch_ms'] is not None:
        try:
            replay = native_replay(game, results['spawn_epoch_ms'])
            receipt['replay'] = str(replay)
        except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
            receipt.update(verdict='capture_incomplete', capture_error=str(error))
            write(record, receipt)
            if verdict != 'client_exited':
                raise ValueError(f'Native capture failed finalization; the world and receipt are preserved: {record}') from error
    if results['observed_credits'] and not results['death'] and verdict == 'credits':
        if not results['native_completed'] or type(results['final_igt_ms']) is not int or results['final_igt_ms'] <= 0:
            raise ValueError('Native credits observed but final SpeedRunIGT receipt is missing or invalid; preserved for inspection.')
        if not 0 < results['observer_rta_ms'] < 600000:
            verdict = 'ten_minute_rta_limit'
        else:
            from official_board import current_benchmark
            course = current_benchmark(read(WEEK / 'course.json'))
            first = course['top_five'][0]
            receipt['benchmark'] = first
            if results['final_igt_ms'] > first['time_ms']:
                verdict = 'slower_than_first'
            else:
                from freeze_take import pack
                pack(world, replay, destination, False)
                render.verify_take(destination, False)
                verdict = 'qualified'
    receipt['verdict'] = verdict
    write(record, receipt)
    print(f"{verdict}  IGT={results['final_igt_ms']}ms  RTA={results['observer_rta_ms']}ms  {world}", flush=True)
    return verdict


def recording_resources(args, work):
    controller = WEEK / 'astra-week113.jar'
    with zipfile.ZipFile(controller) as z:
        if 'dev/aiarena/week113/PracticeLifecycle.class' not in z.namelist():
            raise ValueError('The packaged controller predates practice supervision. Build the current weekly/week113 source and copy its remapped JAR to weekly/week113/astra-week113.jar.')
    setup = render.load_helper('prepare_week_dependency', WEEK / 'prepare.py')
    cache = work / 'dependencies'
    cache.mkdir(exist_ok=True)
    course = read(WEEK / 'course.json')
    deps = {}
    for key, local in [('ranked', args.ranked_jar), ('replaymod', args.replay_jar)]:
        cached = cache / course[key]['filename']
        src = local or (cached if cached.is_file() and setup.filehash(cached,'sha512')==course[key]['hashes']['sha512'] else None)
        deps[key] = setup.dependency(course[key], src, cache)
    skin = select_skin(args.skin, work)
    return skin, deps


def practice(args):
    if args.inspect:
        print(json.dumps(inspect_world(args.inspect.expanduser().resolve()), ensure_ascii=False, indent=2))
        return 0
    work = args.work.expanduser().resolve()
    work.mkdir(parents=True, exist_ok=True)
    winner = work / 'Astra-W113.zip'
    with contextlib.closing(sqlite3.connect(work / 'session-lock.sqlite', timeout=0)) as lock:
        lock.execute('BEGIN IMMEDIATE')
        if not winner.exists():
            prism = render.find_prism(args.prism)
            data = (args.prism_data or render.appdata_dir()).expanduser().resolve()
            if not (data / 'instances').is_dir():
                raise ValueError('Open your existing licensed Prism Launcher installation once, or specify --prism-data.')
            resources = None
            previous = work / 'active.json'
            number = 0
            last_opening = work / 'last-opening.json'
            while not winner.exists() and (args.attempts == 0 or number < args.attempts):
                number += 1
                launch = None
                if previous.is_file():
                    attempt = read(previous)
                    print('Resuming isolated attempt', attempt['instance'], flush=True)
                else:
                    if resources is None:
                        resources = recording_resources(args, work)
                    opening = args.opening
                    if opening == 'alternate':
                        previous_lane = read(last_opening)['opening'] if last_opening.is_file() else None
                        opening = 'shield' if previous_lane == 'fast' else 'fast'
                    attempt = prepare_attempt(data, work, *resources, opening=opening)
                    write(previous, attempt)
                instance = Path(attempt['instance'])
                game = instance / '.minecraft'
                if game_alive(game/'astra-practice', attempt['token']) is None and not attempt.get('reason'):
                    argv = [str(prism), '-d', str(data), '-l', instance.name, '--offline', 'Astra']
                    if args.profile:
                        argv += ['--profile', args.profile]
                    launch = subprocess.Popen(argv, cwd=instance)
                print('Observing', instance, flush=True)
                try:
                    watch(game, game/'saves'/WORLD, attempt, previous, args.reset_pb, launch)
                except KeyboardInterrupt:
                    if not attempt.get('reason'):
                        attempt['reason']='user_stopped'
                        write(previous, attempt)
                    (game/'astra-practice'/(attempt['token']+'.close')).touch()
                    print('Requested normal Minecraft shutdown. Run the same command to resume.', flush=True)
                    return 130
                finish_attempt(attempt, work, winner)
                write(last_opening, {'opening': attempt.get('opening', 'fast')})
                previous.unlink()
            if not winner.exists():
                print(f'No qualifying take in {number} attempts. Private receipts: {work / "receipts"}')
                return 2
        meta, _ = render.verify_take(winner,False)
        print(f"Qualified native take {winner}, IGT {meta['native_speedrunigt_ms']} ms.", flush=True)
        if not args.freeze_only:
            argv = [sys.executable, str(ROOT/'render.py'), '--take', str(winner), '--output', str(args.output.expanduser().resolve())]
            for opt,val in [('prism',args.prism),('prism-data',args.prism_data),('music',args.music),
                            ('ranked-jar',args.ranked_jar),('replay-jar',args.replay_jar)]:
                if val is not None:
                    argv.extend(['--'+opt,str(val.expanduser().resolve())])
            subprocess.run(argv, check=True)
    return 0


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work',type=Path,default=Path.home()/'Astra-Practice-W113',help='Private campaign, resumable across launches')
    p.add_argument('--attempts',type=int,default=0,help='Number of attempts this invocation; zero continues until qualification')
    p.add_argument('--opening', choices=['alternate', 'fast', 'shield'], default='alternate',
                   help='Alternate the fast iron-trigger and shield openings across completed attempts, or select one lane')
    p.add_argument('--reset-pb',action='append',choices=['nether','end'],default=[],help='Optional strict reset when missing this campaign’s prior achieved split')
    p.add_argument('--skin',type=Path);p.add_argument('--music',type=Path)
    p.add_argument('--prism',type=Path);p.add_argument('--prism-data',type=Path);p.add_argument('--profile')
    p.add_argument('--ranked-jar',type=Path);p.add_argument('--replay-jar',type=Path)
    p.add_argument('--output',type=Path,default=Path.cwd()/'Astra-W113-practice-1080p60.mp4')
    p.add_argument('--freeze-only',action='store_true',help='Verify and keep the winning recording without starting export')
    p.add_argument('--inspect',type=Path,metavar='WORLD',help='Print an existing world\'s observer and native timer result without launching Minecraft')
    a=p.parse_args()
    if a.attempts<0:p.error('--attempts must be zero or positive')
    try:sys.exit(practice(a))
    except (OSError,ValueError,KeyError,RuntimeError,sqlite3.Error,zipfile.BadZipFile,subprocess.SubprocessError) as e:p.exit(1,str(e)+'\n')
