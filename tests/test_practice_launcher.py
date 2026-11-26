import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
import zipfile
import zlib

import practice


class PracticeLauncherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.instance = self.root / 'instance'
        self.game = self.instance / '.minecraft'
        self.world = self.game / 'saves' / practice.WORLD
        self.world.mkdir(parents=True)
        (self.world / 'speedrunigt').mkdir()
        self.log = self.world / 'astra-run.jsonl'
        self.attempt = {'instance': str(self.instance), 'token': str(uuid.uuid4())}
        self.dest = self.root / 'Astra-W113.zip'

    def rows(self, events):
        rows = []
        for event, ms in events:
            rows.append({'event': event, 'elapsed_ms': ms, 'epoch_ms': 1000000 + ms})
        self.log.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        observer = practice.Observer(self.log)
        observer.update()
        return observer

    def capture(self):
        directory = self.game / 'replay_recordings/recording'
        directory.mkdir(parents=True)
        archive = directory / 'run.mcpr'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('metaData.json', json.dumps({'protocol':736, 'singleplayer':True, 'date':998000}))
            z.writestr('recording.tmcpr', bytes(20))
        return archive

    def raw_capture(self, name='run'):
        changed = self.game / 'replay_recordings/recording' / (name + '.mcpr.tmp') / 'changed'
        changed.mkdir(parents=True)
        meta = {'mcversion': '1.16.1', 'protocol': 736, 'singleplayer': True, 'date': 998000,
                'fileFormat': 'MCPR', 'fileFormatVersion': 14, 'duration': 7000}
        stream = struct.pack('>ii', 120, 1) + b'\x01' + struct.pack('>ii', 7000, 2) + b'\xac\x01'
        (changed / 'recording.tmcpr').write_bytes(stream)
        (changed / 'metaData.json').write_text(json.dumps(meta), encoding='utf-8')
        (changed / 'mods.json').write_text('{"requiredMods":[]}', encoding='utf-8')
        (changed / 'recording.tmcpr.crc32').write_text(str(zlib.crc32(stream)), encoding='ascii')
        supervisor = self.game / 'astra-practice'
        supervisor.mkdir(exist_ok=True)
        (supervisor / (self.attempt['token'] + '.ready')).touch()
        (supervisor / (self.attempt['token'] + '.lock')).touch()
        (self.world / 'session.lock').write_bytes(b'\xe2\x98\x83')
        return changed

    def recover(self):
        return practice.native_replay(self.game, 1000000, self.world, self.attempt['token'])

    def test_completed_raw_capture_recovers_exact_bytes_and_is_idempotent(self):
        changed = self.raw_capture()
        original = {p.name: p.read_bytes() for p in changed.iterdir()}
        replay = self.recover()
        with zipfile.ZipFile(replay) as archive:
            self.assertEqual({n: archive.read(n) for n in archive.namelist()}, original)
        self.assertEqual({p.name: p.read_bytes() for p in changed.iterdir()}, original)
        self.assertEqual(self.recover(), replay)
        (changed / 'mods.json').write_text('{"requiredMods":["different"]}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Conflicting'):
            self.recover()

    def test_open_java_world_lock_prevents_recovery(self):
        changed = self.raw_capture()
        script = ('from pathlib import Path\nimport sys,practice\n'
                  'with practice.closed_world(Path(sys.argv[1])):\n'
                  ' print("locked",flush=True)\n sys.stdin.read(1)\n')
        with subprocess.Popen([sys.executable, '-c', script, str(self.world)], cwd=practice.ROOT,
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as child:
            try:
                self.assertEqual(child.stdout.readline().strip(), 'locked')
                with self.assertRaises(OSError):
                    self.recover()
                self.assertFalse(changed.parent.with_suffix('').exists())
            finally:
                child.communicate('x', timeout=5)

    def test_live_unconfirmed_and_duplicate_raw_captures_are_refused(self):
        changed = self.raw_capture()
        for running in (True, None):
            with self.subTest(running=running), patch('practice.game_alive', return_value=running):
                with self.assertRaisesRegex(ValueError, 'JVM closes'):
                    self.recover()
        self.raw_capture('another')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.recover()
        self.assertFalse(changed.parent.with_suffix('').exists())

    def test_raw_requires_native_clock_crc_complete_frames_and_matching_spawn(self):
        changed = self.raw_capture()
        stream = changed / 'recording.tmcpr'
        crc = changed / 'recording.tmcpr.crc32'
        original = {p.name: p.read_bytes() for p in changed.iterdir()}
        for fault in ('bad_crc', 'missing_crc', 'short_payload', 'short_header', 'backwards', 'duration', 'spawn'):
            with self.subTest(fault=fault):
                for name, contents in original.items():
                    (changed / name).write_bytes(contents)
                if fault == 'bad_crc': crc.write_text('42', encoding='ascii')
                elif fault == 'missing_crc': crc.unlink()
                elif fault in ('short_payload', 'short_header', 'backwards'):
                    stream.write_bytes({'short_payload': stream.read_bytes()[:-1], 'short_header': stream.read_bytes()+b'x',
                                        'backwards': stream.read_bytes()+struct.pack('>iiB',6900,1,8)}[fault])
                    crc.write_text(str(zlib.crc32(stream.read_bytes())), encoding='ascii')
                else:
                    meta = practice.read(changed / 'metaData.json')
                    meta['duration' if fault == 'duration' else 'date'] = 4000
                    (changed / 'metaData.json').write_text(json.dumps(meta), encoding='utf-8')
                with self.assertRaises(ValueError):
                    self.recover()
                self.assertFalse(changed.parent.with_suffix('').exists())

    def test_incomplete_observer_line_waits(self):
        observer = self.rows([('spawn',0)])
        with self.log.open('ab') as f:
            f.write(b'{"event":"dea')
        observer.update()
        self.assertNotIn('death',observer.events)
        with self.log.open('ab') as f:
            f.write(b'th","elapsed_ms":21}\n')
        observer.update()
        self.assertEqual(practice.decision(observer),'death')

    def test_opt_in_previous_split_and_first_record(self):
        active = self.rows([('spawn',0),('state',1001)])
        self.assertIsNone(practice.decision(active))
        self.assertEqual(practice.decision(active,{'nether':1001000}),'missed_prior_nether')
        saved = self.rows([('spawn',0),('enter_nether',999),('state',2000)])
        self.assertIsNone(practice.decision(saved,{'nether':1001000}))
        limit = self.rows([('spawn',0),('state',600000)])
        self.assertEqual(practice.decision(limit),'ten_minute_rta_limit')

    def test_death_archives_genuine_capture_and_never_freezes(self):
        self.rows([('spawn',0),('death',30000)])
        archive=self.capture()
        practice.write(self.world/'speedrunigt/record.json',{'is_completed':False,'final_igt':28000})
        self.attempt['reason']='death'
        self.assertEqual(practice.finish_attempt(self.attempt,self.root,self.dest),'death')
        self.assertFalse(self.dest.exists())
        result=practice.read(self.root/'receipts'/(self.attempt['token']+'.json'))
        self.assertEqual(result['replay'],str(archive))

    def test_missing_native_capture_preserves_failure_receipt(self):
        self.rows([('spawn',0),('death',44)])
        self.attempt['reason']='death'
        with self.assertRaisesRegex(ValueError,'failed finalization'):
            practice.finish_attempt(self.attempt,self.root,self.dest)
        result=practice.read(self.root/'receipts'/(self.attempt['token']+'.json'))
        self.assertEqual(result['verdict'],'capture_incomplete')

    def test_campaign_alternates_openings_after_a_restart(self):
        from types import SimpleNamespace
        instances = self.root/'prism'/'instances'
        instances.mkdir(parents=True)
        args = SimpleNamespace(work=self.root/'campaign', inspect=None, prism=None, prism_data=instances.parent,
                               attempts=1, opening='alternate', wait_for_piglins=True, initial_view_distance=16, reset_pb=[], profile=None)
        selected = []
        def prepare(data, work, skin, deps, opening, wait_for_piglins, initial_view_distance):
            self.assertEqual(initial_view_distance,16)
            self.assertTrue(wait_for_piglins)
            selected.append(opening)
            return {'opening': opening, 'instance': str(self.instance), 'token': str(uuid.uuid4())}
        def finish(attempt, work, destination):
            return 'death'
        with patch('render.find_prism'), patch('practice.recording_resources', return_value=(None, {})), \
             patch('practice.prepare_attempt', side_effect=prepare), patch('practice.game_alive', return_value=True), \
             patch('practice.watch'), patch('practice.finish_attempt', side_effect=finish):
            for _ in range(3):
                self.assertEqual(practice.practice(args),2)
        self.assertEqual(selected,['fast','shield','fast'])

    def test_fortress_recall_requires_six_rods_shortcuts_and_physical_arrival(self):
        from world_evidence import stasis_shortcuts
        def key(ms, before, after):
            return dict(event='render_distance_shortcut', dim='nether', elapsed_ms=ms, before=before, after=after)
        def state(tick, ms, rods, x, y, z):
            return dict(event='state', dim='nether', elapsed_ms=ms, run_tick=tick, x=x, y=y, z=z,
                        inventory=[[0,'minecraft:blaze_rod',rods]])
        lower, raise_distance = key(100,3,2), key(300,2,3)
        cage = state(5,250,6,-8,57,159)
        ledge = state(6,350,6,-82,74,175)
        evidence = stasis_shortcuts([lower, cage, raise_distance, ledge])
        self.assertEqual(evidence['ledge_arrival_ms'],350)
        self.assertGreater(evidence['recall_displacement_blocks'],70)
        with self.assertRaisesRegex(ValueError,'after collecting six'):
            stasis_shortcuts([lower,key(200,2,3),cage,ledge])
        with self.assertRaisesRegex(ValueError,'single-tick'):
            stasis_shortcuts([lower,cage,raise_distance,dict(ledge,run_tick=8)])
        with self.assertRaisesRegex(ValueError,'single-tick'):
            stasis_shortcuts([lower,cage,raise_distance,dict(ledge,x=-9,y=57,z=160)])

    def test_time_equality_goes_to_real_verifiers(self):
        self.rows([('spawn',0),('credits',335528)])
        self.capture()
        self.attempt['reason']='credits'
        practice.write(self.world/'speedrunigt/record.json',{'is_completed':True,'final_igt':335528})
        board={'top_five':[{'time_ms':335528}]}
        with patch('official_board.current_benchmark',return_value=board), patch('freeze_take.pack') as pack, patch('render.verify_take') as verify:
            self.assertEqual(practice.finish_attempt(self.attempt,self.root,self.dest),'qualified')
            pack.assert_called_once()
            verify.assert_called_once_with(self.dest,False)
        board['top_five'][0]['time_ms']=335527
        with patch('official_board.current_benchmark',return_value=board), patch('freeze_take.pack') as pack:
            self.assertEqual(practice.finish_attempt(self.attempt,self.root,self.dest),'slower_than_first')
            pack.assert_not_called()
