import json
from pathlib import Path
import struct
import tempfile
import unittest
import uuid
import zipfile

from presentation import music, receipts, replay_pov


def jsonl(path, rows):
    path.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8')


class FilmReplayTests(unittest.TestCase):
    def test_archive_preserves_packets_and_switches_entity_at_original_spawn(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            who = uuid.uuid4()
            login = bytes([2]) + who.bytes + b'\x05Astra'
            spawn = lambda entity: bytes([4, entity]) + who.bytes + bytes(26)
            content = b''.join(struct.pack('>ii', t, len(payload)) + payload for t, payload in
                               [(0, login), (20, spawn(7)), (900, spawn(9)), (1200, b'\x01')])
            with zipfile.ZipFile(root / 'original.mcpr', 'x') as archive:
                archive.writestr('metaData.json', json.dumps({'mcversion':'1.16.1','protocol':736,'date':100,'duration':1200}))
                archive.writestr('recording.tmcpr', content)
            pose = dict(x=0,y=64,z=0,yaw=0,pitch=0)
            journal = root / '真实记录.jsonl'
            jsonl(journal, [dict(event='spawn',elapsed_ms=0,epoch_ms=150,**pose),
                            dict(event='state',elapsed_ms=600,gui_title='物品栏',**pose),
                            dict(event='credits',elapsed_ms=1000)])
            hud = {'route':'direct','previous_best_ms':{'end':200},'current_splits_ms':{'dragon':760}}
            view = replay_pov.build(root/'original.mcpr',journal,root/'POV.mcpr','Astra',None,0,None,hud=hud,credits_tail_ms=18000)
            self.assertEqual(view['video_duration_ms'],1150)
            with zipfile.ZipFile(root/'POV.mcpr') as archive:
                self.assertEqual(archive.read('recording.tmcpr'),content)
                pose_path = json.loads(archive.read('timelines.json'))[replay_pov.PRESET][1]
                self.assertEqual([(k['time'],k['properties']['spectate']) for k in pose_path['keyframes']],
                                 [(0,7),(849,7),(850,9),(1150,9)])
                baked = json.loads(archive.read(replay_pov.OVERLAY))
                for key in hud:self.assertEqual(baked[key],hud[key])
            with self.assertRaises(FileExistsError):
                replay_pov.build(root/'original.mcpr',journal,root/'POV.mcpr','Astra',None,0,None)

    def test_native_pb_excludes_future_course_and_post_death(self):
        with tempfile.TemporaryDirectory() as tmp:
            game = Path(tmp)
            world = game/'saves/test'; world.mkdir(parents=True)
            history = game/'history'; history.mkdir()
            config = game/'config'; config.mkdir()
            (config/'astra-film.json').write_text(json.dumps({'rules':'w113','route':'nether','history':str(history)}))
            own = str(uuid.uuid4());(world/'astra-film-attempt.txt').write_text(own)
            header = dict(event='spawn',course='w113|nether|seed=a',wall_ms=1000,player='Astra')
            jsonl(world/'astra-run.jsonl',[dict(event='spawn',epoch_ms=955)])
            jsonl(history/(own+'.jsonl'),[header, dict(event='dragon',ms=310)])
            jsonl(history/'older.jsonl',[dict(header,wall_ms=500),dict(event='nether',ms=65),dict(event='death',ms=75),dict(event='end',ms=100)])
            jsonl(history/'later.jsonl',[dict(header,wall_ms=1500),dict(event='nether',ms=5)])
            jsonl(history/'other.jsonl',[dict(header,course='w113|nether|seed=b',wall_ms=600),dict(event='nether',ms=3)])
            self.assertEqual(receipts.film_data(world),{'route':'nether','previous_best_ms':{'nether':65},'current_splits_ms':{'dragon':355}})

    def test_artist_original_does_not_select_sped_or_slowed_version(self):
        from html import escape
        album = {'artist':music.ARTIST,'current':{'title':'Sleepwalker'},'trackinfo':[
            {'track_id':123,'title':'Sleepwalker (Ultra Slowed & Reverb)','duration':317.562,'file':{'mp3-128':'wrong'}},
            {'track_id':music.TRACK_ID,'title':'Sleepwalker','duration':212.0,'file':{'mp3-128':'right'}}]}
        parser=music.ReleasePage();parser.feed('<script data-tralbum="'+escape(json.dumps(album),quote=True)+'"></script>')
        self.assertEqual(parser.stream,'right')


if __name__ == '__main__':
    unittest.main()
