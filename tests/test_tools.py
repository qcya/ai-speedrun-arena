import gzip
import json
from pathlib import Path
import tempfile
import unittest

from tools.prepare_course import prepare, level_bytes
from tools.search import candidates
from tools.weekly import summarize
from arena.model import ArenaError

ROOT = Path(__file__).resolve().parents[1]


class ProjectToolTests(unittest.TestCase):
    def test_world_creation_never_replaces_existing_save(self):
        with tempfile.TemporaryDirectory() as tmp:
            world = prepare(tmp)
            original = (world / 'level.dat').read_bytes()
            self.assertEqual(original, level_bytes())
            self.assertTrue(gzip.decompress(original).startswith(b'\x0a\0\0'))
            self.assertTrue((world / 'datapacks/arena_course/pack.mcmeta').is_file())
            with self.assertRaises(FileExistsError):
                prepare(tmp)
            self.assertEqual((world / 'level.dat').read_bytes(), original)

    def test_search_mutates_only_designated_field(self):
        plan = json.loads((ROOT / 'examples/astra-bed.json').read_text())
        variants = candidates(plan, 3, 'pitch', ['29', '42'])
        self.assertEqual([v[1]['steps'][2]['pitch'] for v in variants], [29, 42])
        self.assertEqual(plan['steps'][2]['pitch'], 35)
        with self.assertRaises(ArenaError):
            candidates(plan, 3, 'pitch', ['29', '29'])
        with self.assertRaises(ArenaError):
            candidates(plan, 3, 'pitch', ['100'])

    def test_weekly_seeds_keep_original_precision_and_champion_order(self):
        seed = '-9223372036854775808'
        document = {'status':'success', 'data':{'id':90, 'endsAt':1,
                    'seed':dict(overworld=seed, nether=seed, theEnd=seed, rng=seed, flags=3),
                    'leaderboard':[{'rank':2, 'player':{'nickname':'B'}, 'time':300000, 'replayExist':False},
                                   {'rank':1, 'player':{'nickname':'A'}, 'time':298000, 'replayExist':True}]}}
        result = summarize(document)
        self.assertEqual(result['seeds']['rng'], seed)
        self.assertEqual(result['reference_runs'][0]['player'], 'A')
