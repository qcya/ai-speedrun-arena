"""Run with python3 -m unittest discover -s <timer directory>."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from hashlib import sha256

from practice_attempts import load_config, references, run_batch
from split_timer import Attempt, classify, read_attempt


MOCK = """
import json,sys,time,os
from pathlib import Path
folder,log,mode=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
origin=round(time.time()*1000)-50
first=len(list(folder.parent.glob('attempt-*')))==1
def row(event,tick,delta,**data):
    with log.open('a') as f:
        print(json.dumps(dict(event=event,time_ms=origin+delta,tick=tick,**data)),file=f)
row('spawn',0,0)
row('state',1,1,dimension='minecraft:overworld',hp=20,inv={})
row('state',2,4,dimension='minecraft:overworld',hp=20,inv={'purple_bed':1})
if mode=='death-then-finish' and first:
    row('state',3,8,dimension='minecraft:overworld',hp=0,inv={})
elif mode=='late':
    row('state',3,30,dimension='minecraft:overworld',hp=20,inv={})
else:
    row('state',3,8,dimension='minecraft:the_end',hp=20,inv={'purple_bed':1},dragon_hp=200)
    row('state',4,11,dimension='minecraft:the_end',hp=20,inv={},dragon_hp=0)
    row('end_credits',5,15)
(folder/'pid').write_text(str(os.getpid()))
time.sleep(30)
"""


class PracticeTests(unittest.TestCase):
    def config(self,d,mode):
        (d/'world.dat').write_bytes(b'fixture, mock game only')
        (d/'mock.py').write_text(MOCK)
        path=d/'config.json'
        path.write_text(json.dumps(dict(course=dict(version="fake",rules="test",seeds={"minecraft:overworld":"5"},
                                   template_sha256=sha256((d/'world.dat').read_bytes()).hexdigest()),
                        world_template="world.dat",route="direct",max_attempts=2,
                        interval_seconds=.02,startup_timeout=2,
                        launch=[sys.executable,str(d/'mock.py'),"{attempt_dir}","{log}",mode],
                        finalize=[sys.executable,"-c","import pathlib;pathlib.Path('finalized').touch()"])))
        return load_config(path)

    def test_real_child_restart_and_finish_only_owned_group(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp)
            cfg=self.config(d,'death-then-finish')
            untouched=subprocess.Popen([sys.executable,"-c","import time;time.sleep(5)"])
            try:
                self.assertEqual(run_batch(cfg,d/'trials',[]),0)
                folders=sorted((d/'trials').glob('attempt-*'),key=lambda p:(p/'result.json').stat().st_mtime_ns)
                a,b=[json.loads((p/'result.json').read_text()) for p in folders]
                self.assertEqual((a["status"],a['reason']),("reset","death"))
                self.assertEqual((b["status"],b["previous_best_ms"]["first_bed"]),("finished",4))
                self.assertEqual(b["splits_ms"]["enter_end"],8)
                self.assertTrue((folders[1]/'finalized').is_file())
                self.assertIsNotNone(read_attempt(folders[0]/'observed-run.jsonl'))
                self.assertIsNone(untouched.poll())
                for folder in folders:
                    with self.assertRaises(ProcessLookupError):os.kill(int((folder/'pid').read_text()),0)
                self.assertEqual(references(d/'trials',{**cfg,"route":"nether"}),[])
                self.assertEqual(references(d/'trials',{**cfg,"course":{**cfg['course'],"seeds":{"minecraft:overworld":"6"}}}),[])
            finally:
                untouched.terminate()
                untouched.wait()

    def test_pb_gate_on_unreached_split(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp)
            cfg=self.config(d,'finish')
            self.assertEqual(run_batch(cfg,d/'trials',[]),0)
            cfg['launch'][-1]='late'
            cfg['gates']=['enter_end']
            cfg['max_attempts']=1
            self.assertEqual(run_batch(cfg,d/'trials',[]),10)
            results=[json.loads(p.read_text()) for p in (d/'trials').glob('*/result.json')]
            self.assertEqual([r['reason'] for r in results if r['status']=='reset'],['enter_end'])

    def test_grace_and_deadline_boundaries(self):
        run=Attempt(1000,1600,{"first_bed":400})
        self.assertEqual(classify(run,{"enter_end":700},["enter_end"],0,600,1900)[0],"running")
        run.last=1801
        self.assertEqual(classify(run,{"enter_end":700},["enter_end"],100,600,1900)[0],"reset")
        run.splits["finish"]=600000
        self.assertEqual(classify(run,{},[],0,600,1000000),("reset","time_limit"))


if __name__=="__main__":unittest.main()
