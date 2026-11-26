"""Resolve the original Sleepwalker selected for the project, for local muxing only."""
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

RELEASE = 'https://stmofficial.bandcamp.com/album/sleepwalker'
ARTIST = 'akiaura, LONOWN, STM'
TRACK_ID = 2865621735
TRACK_SECONDS = 212.0
WEEK29_SHA256 = '2fede45216e0566cd3881588d7cbdbff81d1dcc21a11edeeca4884f2568d326a'


def duration(path):
    result = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries',
                             'stream=duration:format=duration', '-of', 'json', str(path)], capture_output=True)
    if result.returncode:
        return 0.0
    data = json.loads(result.stdout)
    if not data.get('streams'):
        return 0.0
    try:
        return float(data['streams'][0].get('duration') or data['format']['duration'])
    except (KeyError, ValueError):
        return 0.0


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def local_source(folders):
    paths = sorted({p for folder in folders if folder.is_dir() for p in folder.iterdir() if p.is_file()})
    for p in paths:
        name = p.stem.casefold()
        if (p.suffix.casefold() in ('.mp3', '.flac', '.wav', '.m4a', '.ogg', '.aac') and
                'sleepwalker' in name and not any(k in name for k in ('slow', 'sped', 'remix', 'reverb')) and
                abs(duration(p) - TRACK_SECONDS) < 1.0):
            return p
    for p in paths:
        if p.suffix.casefold() == '.mp4' and ('sleepwalker' in p.stem.casefold() or '29' in p.stem):
            if digest(p) == WEEK29_SHA256:
                return p
    return None


class ReleasePage(HTMLParser):
    def __init__(self):
        super().__init__()
        self.stream = None

    def handle_starttag(self, tag, attrs):
        raw = dict(attrs).get('data-tralbum')
        if not raw:
            return
        album = json.loads(raw)
        if album.get('artist') != ARTIST or album.get('current', {}).get('title') != 'Sleepwalker':
            return
        for track in album.get('trackinfo', []):
            if (track.get('track_id') == TRACK_ID and track.get('title') == 'Sleepwalker' and
                    abs(track.get('duration', 0) - TRACK_SECONDS) < .1):
                self.stream = (track.get('file') or {}).get('mp3-128')


def artist_source(destination):
    request = Request(RELEASE, headers={'User-Agent': 'Astra-Local-Film/1.0'})
    with urlopen(request, timeout=25) as response:
        page = ReleasePage()
        page.feed(response.read(4 << 20).decode('utf-8'))
    u = urlsplit(page.stream or '')
    if u.scheme != 'https' or not u.hostname or not u.hostname.endswith('.bcbits.com'):
        raise ValueError('The artist page has no original-track URL. Supply your local --music copy.')
    with urlopen(page.stream, timeout=35) as response, destination.open('xb') as out:
        shutil.copyfileobj(response, out)
    if abs(duration(destination) - TRACK_SECONDS) >= 1:
        raise ValueError('The file returned by the artist does not match the selected original recording')
    return destination


def mux(video, track, output, frames, has_game_audio):
    """Keep the recorded video intact and play the selected music at most twice."""
    seconds = frames / 60
    one_play = duration(track)
    # The archived Week 29 film has already looped the song. Only its first
    # original-length pass can be used as a repeatable source.
    if track.suffix.lower() == '.mp4' and digest(track) == WEEK29_SHA256:
        one_play = TRACK_SECONDS
    if one_play <= 0 or seconds > 2 * one_play:
        raise ValueError("The complete video must fit within two plays of the selected music")

    audio = (f'[1:a:0]atrim=duration={one_play:.8f},asetpts=PTS-STARTPTS,asplit[one][two];'
             f'[one][two]concat=n=2:v=0:a=1,atrim=duration={seconds:.8f},volume=-10dB')
    if has_game_audio:
        audio += '[music];[0:a:0][music]amix=inputs=2:duration=longest:normalize=0'
    subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-n', '-i', str(video), '-i', str(track),
                    '-filter_complex', audio + '[mix]', '-map', '0:v:0', '-map', '[mix]',
                    '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-t', f'{seconds:.8f}',
                    '-movflags', '+faststart', str(output)], check=True)
