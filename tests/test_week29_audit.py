import gzip
import json
import tempfile
import unittest
from pathlib import Path

from weekly.week29.audit import audit, END, OVERWORLD


def sample(tick, dimension=OVERWORLD, hp=20, inventory=None, **extra):
    return dict(event="state", tick=tick, time_ms=1000+50*tick, dimension=dimension,
                x=-236.5, y=69.0, z=180.5, hp=hp, food=20, inv=inventory or {}, **extra)


def fixture():
    return [
        {"event": "spawn", "tick": 0, "time_ms": 1000},
        sample(1),
        sample(11, inventory={"iron_pickaxe": 1, "purple_bed": 3, "white_bed": 2}),
        sample(21, END, dragon_hp=200.0, dragon_phase="HoldingPattern (#0)"),
        sample(31, END, dragon_hp=1.0, dragon_phase="Dying (#9)"),
        sample(41, END),
        {"event": "end_credits", "tick": 41, "time_ms": 3050},
    ]


def check(rows, compressed=False):
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder)/("observed-run.jsonl.gz" if compressed else "observed-run.jsonl")
        data = "".join(json.dumps(row)+"\n" for row in rows).encode()
        path.write_bytes(gzip.compress(data) if compressed else data)
        return audit(path)


class Week29AuditTests(unittest.TestCase):
    def test_plain_and_gzip_accept_tied_credits_tick_and_record_measured_splits(self):
        report = check(fixture())
        self.assertEqual(report, check(fixture(), compressed=True))
        self.assertTrue(report["verified"])
        self.assertEqual(report["local_elapsed_ms"], 2050)
        self.assertEqual(report["maximum_simultaneous_beds"], 5)
        self.assertEqual(report["minimum_observed_dragon_hp"], 1.0)
        self.assertNotIn("dragon_zero_hp", report["splits"])
        self.assertNotIn("position", report["splits"]["end_credits"])
        self.assertEqual(report["splits"]["beds"]["position"]["x"], -236.5)

    def test_partial_or_missing_dragon_does_not_imply_victory(self):
        report = check(fixture()[:3])
        self.assertEqual(report["status"], "incomplete")
        self.assertIsNone(report["local_elapsed_ms"])
        self.assertIn("end_credits", report["missing"])
        rows = fixture()
        rows[4] = sample(31, END, stage="victory")
        report = check(rows)
        self.assertFalse(report["verified"])
        self.assertIn("dragon_zero_or_dying", report["missing"])

    def test_zero_hp_and_player_death_are_separate_observations(self):
        rows = fixture()
        rows[4] = sample(31, END, hp=0, dragon_hp=0.0, dragon_phase="HoldingPattern (#0)")
        report = check(rows)
        self.assertTrue(report["verified"])
        self.assertTrue(report["observed_player_death"])
        self.assertEqual(report["player_health"]["minimum_in_end"], 0)
        self.assertEqual(report["player_health"]["final_observed"], 20)
        self.assertEqual(report["splits"]["dragon_zero_hp"]["tick"], 31)

    def test_integrity_errors_cannot_be_certified(self):
        for label in ("gap", "double_spawn", "clock", "stocked", "wrong_spawn", "after_credits", "missing_final_sample"):
            rows = fixture()
            if label == "gap":
                del rows[2]
            elif label == "double_spawn":
                rows.insert(1, rows[0].copy())
            elif label == "clock":
                rows[3]["time_ms"] = 1001
            elif label == "stocked":
                rows[1]["inv"] = {"iron_pickaxe": 1}
            elif label == "wrong_spawn":
                rows[1]["x"] = 116.5
            elif label == "after_credits":
                rows.append(sample(51, END))
            else:
                rows[-1]["tick"] = 51
            with self.subTest(label=label):
                self.assertEqual(check(rows)["status"], "invalid")
