import array
import math
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from presentation import music


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'requires FFmpeg')
class SoundtrackTests(unittest.TestCase):
    def test_two_passes_lower_only_the_music_and_preserve_frames(self):
        def run(*args):
            return subprocess.check_output(['ffmpeg', '-v', 'error', '-nostdin', *map(str, args)])

        def rms(path, frequency, offset):
            data = run('-ss', offset, '-i', path, '-t', '0.25', '-vn', '-ac', '1', '-ar', '48000',
                       '-af', f'bandpass=f={frequency}:width_type=h:w=30', '-f', 'f32le', '-')
            samples = array.array('f', data)
            return math.sqrt(sum(x*x for x in samples) / len(samples))

        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            track = root / 'track.wav'
            run('-f', 'lavfi', '-i', 'sine=frequency=1700:duration=1', track)
            for has_game in (False, True):
                with self.subTest(has_game=has_game):
                    video = root / f'game-{has_game}.mp4'
                    output = root / f'film-{has_game}.mp4'
                    inputs = ['-f','lavfi','-i','sine=frequency=430:duration=1.8'] if has_game else []
                    run('-f','lavfi','-i','color=s=32x32:r=60:d=1.8', *inputs, '-c:v','libx264', video)
                    music.mux(video, track, output, 108, has_game)
                    for t in ('0.2','1.2'):
                        gain = rms(output, 1700, t) / rms(track, 1700, '0.2')
                        self.assertAlmostEqual(20 * math.log10(gain), -10, delta=0.35)
                    if has_game:
                        gain = rms(output, 430, '0.2') / rms(video, 430, '0.2')
                        self.assertAlmostEqual(20 * math.log10(gain), 0, delta=0.35)
                    probe = subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0',
                                                     '-show_entries','stream=nb_frames','-of','csv=p=0', str(output)])
                    self.assertEqual(int(probe),108)
            with self.assertRaisesRegex(ValueError,'two plays'):
                music.mux(video,track,root / 'long.mp4',125,False)
