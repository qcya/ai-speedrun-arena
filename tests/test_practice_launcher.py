import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid
import zipfile

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
                               attempts=1, opening='alternate', wait_for_piglins=True, reset_pb=[], profile=None)
        selected = []
        def prepare(data, work, skin, deps, opening, wait_for_piglins):
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
