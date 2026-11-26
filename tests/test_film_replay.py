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
    def test_render_distance_uses_each_native_packet_once_and_keeps_source_stream(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            who=uuid.uuid4().bytes
            component={'text':'','extra':[{'translate':'debug.prefix','bold':True,'color':'yellow'},
                                          ' ',{'translate':'debug.cycle_renderdistance.message','with':['5']}]}
            encoded=json.dumps(component).encode()
            self.assertTrue(128<=len(encoded)<16384)
            native=bytes([14,len(encoded)%128+128,len(encoded)//128])+encoded+bytes([1])+bytes(16)
            packets=[(0,bytes([2])+who+b'\x05Astra'),(20,bytes([4,7])+who+bytes(26)),
                     (151,native),(1700,b'\x01')]
            stream=b''.join(struct.pack('>ii',t,len(packet))+packet for t,packet in packets)
            source=root/'original.mcpr'
            with zipfile.ZipFile(source,'x') as archive:
                archive.writestr('recording.tmcpr',stream)
                archive.writestr('metaData.json',json.dumps({'date':100,'mcversion':'1.16.1','protocol':736,'duration':1700}))
            pose=dict(x=0,y=64,z=0,yaw=0,pitch=0)
            step=lambda t,a,b,k:dict(event='render_distance_shortcut',elapsed_ms=t,before=a,after=b,shortcut=k)
            journal=root/'run.jsonl'
            jsonl(journal,[dict(event='spawn',elapsed_ms=0,epoch_ms=150,**pose),
                           step(100,4,5,'F3+F'),step(150,5,4,'Shift+F3+F'),step(200,4,5,'F3+F'),
                           dict(event='state',elapsed_ms=600,view_distance=8,**pose),
                           dict(event='credits',elapsed_ms=1500)])
            movie=root/'pov.mcpr'
            receipt=replay_pov.build(source,journal,movie,'Astra',None,0,None)
            with zipfile.ZipFile(movie) as archive:
                self.assertEqual(archive.read('recording.tmcpr'),stream)
                events=json.loads(archive.read(replay_pov.OVERLAY))['render_distance_feedback']
            self.assertEqual([(e['elapsed_ms'],e['after'],e['source']) for e in events],
                             [(101,5,'packet'),(150,4,'keyboard'),(200,5,'keyboard')])
            self.assertEqual(events[0]['component'],component)
            self.assertEqual(events[2]['component'],component)
            self.assertEqual(receipt['distance_feedback_local'],2)

    def test_render_distance_ignores_state_changes_and_rejects_invalid_shortcuts(self):
        self.assertEqual(replay_pov.distance_feedback([{'event':'state','view_distance':2}],[],0),[])
        for before,after,key in [(2,1,'Shift+F3+F'),(3,5,'F3+F'),(5,4,'F3+F'),
                                 (True,2,'F3+F'),(2,3,'F3'),(3,3,'F3+F')]:
            with self.subTest(before=before,after=after,key=key),self.assertRaises(ValueError):
                replay_pov.distance_feedback([dict(event='render_distance_shortcut',elapsed_ms=100,
                                                   before=before,after=after,shortcut=key)],[],0)

    def test_native_feedback_age_uses_observed_ticks_and_matches_localized_packet(self):
        line={'text':'','extra':[{'text':'[调试]：','color':'yellow','bold':True},{'text':' 渲染距离：3'}]}
        text=json.dumps(line,ensure_ascii=False,separators=(',',':')).encode()
        data=bytes([14,len(text)])+text+b'\x01'+bytes(16)
        value,actual=replay_pov.distance_packet(data,1)
        self.assertEqual((value,actual),(3,line))
        self.assertIsNone(replay_pov.distance_packet(data[:-17]+b'\x02'+bytes(16),1))
        self.assertIsNone(replay_pov.distance_packet(data[:-16]+bytes([1])*16,1))
        rows=[dict(event='state',elapsed_ms=29110,run_tick=380),
              dict(event='render_distance_shortcut',elapsed_ms=29744,run_tick=387,before=2,after=3,shortcut='F3+F'),
              dict(event='render_distance_shortcut',elapsed_ms=29745,run_tick=387,before=3,after=4,shortcut='F3+F')]
        result=replay_pov.distance_feedback(rows,[(29770,value,actual)],0)
        self.assertEqual([(x['source'],x['chat_tick']) for x in result],[('keyboard',387),('packet',387)])

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
