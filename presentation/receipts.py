"""Extract only the pre-run split records that the native HUD displayed."""
import json
from pathlib import Path
import uuid

SPLITS = ('nether', 'end', 'dragon', 'finish')


def film_data(world):
    """Bind the HUD receipt to this world's attempt marker and preserve its local PBs."""
    world = Path(world)
    instance = world.parents[1]
    profile = json.loads((instance / 'config/astra-film.json').read_text(encoding='utf-8'))
    history = Path(profile.get('history', instance / 'astra-film/history'))
    attempt = str(uuid.UUID((world / 'astra-film-attempt.txt').read_text().strip()))
    records = [json.loads(line) for line in (history / (attempt + '.jsonl')).read_text(encoding='utf-8').splitlines()]
    head = records[0]
    if head.get('event') != 'spawn' or head.get('player') != 'Astra':
        raise ValueError('The recorded world does not have a matching Astra HUD receipt')
    if not head['course'].startswith(profile['rules'] + '|' + profile['route'] + '|'):
        raise ValueError('Profile rules or route differ from the original world receipt')
    if 'previous_best_ms' in head:
        previous = head['previous_best_ms']
    else:
        previous = {}
        for path in history.glob('*.jsonl'):
            with path.open(encoding='utf-8') as stream:
                try:
                    start = json.loads(next(stream))
                    if start.get('course') != head['course'] or start['wall_ms'] >= head['wall_ms']:
                        continue
                    for line in stream:
                        row = json.loads(line)
                        event = row['event']
                        if event == 'death':
                            break
                        if event in SPLITS and isinstance(row.get('ms'), int) and row['ms'] >= 0:
                            previous[event] = min(previous.get(event, row['ms']), row['ms'])
                except (ValueError, KeyError, StopIteration):
                    # The native live HUD can read complete lines from an in-progress attempt.
                    continue
    if any(key not in SPLITS or type(ms) is not int or ms < 0 for key, ms in previous.items()):
        raise ValueError('Malformed captured prior split values')
    with (world / 'astra-run.jsonl').open(encoding='utf-8') as stream:
        observer_spawn = json.loads(next(stream))
    current = {}
    if 'epoch_ms' in observer_spawn:
        offset = head['wall_ms'] - observer_spawn['epoch_ms']
        for row in records[1:]:
            if row.get('event') == 'death':
                break
            if row.get('event') in SPLITS:
                current.setdefault(row['event'], max(0, row['ms'] + offset))
    return {'route': profile['route'], 'previous_best_ms': previous, 'current_splits_ms': current}
