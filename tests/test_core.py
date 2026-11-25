import copy
import json
import math
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path

from arena import core


PLAN = {"version": 1, "agent": "测试 agent",
        "steps": [{"ticks": 40, "keys": ["forward", "sprint"], "label": "冲刺"},
                  {"ticks": 2, "keys": ["use"], "require_target": "block", "slot": 3}],
        "goal": {"type": "displacement", "blocks": 4}}
START = {"x": 1.5, "y": 64.0, "z": -2.5, "yaw": 90, "pitch": 0,
         "dimension": "minecraft:overworld", "health": 20, "food": 20,
         "selected_slot": 0, "inventory": [], "saturation": 5, "on_ground": True,
         "sprinting": False, "sneaking": False, "experience_level": 0, "experience_progress": 0,
         "velocity_x": 0.0, "velocity_y": -0.0784, "velocity_z": 0.0, "effects": []}
SESSION = "9" * 32


def result_for(run_id, **changes):
    result = {"id": run_id, "status": "success", "ticks": 21, "reason": "goal",
              "trace": [{"tick": 0, **START},
                        {"tick": 21, **{**START, "z": 2.5}}],
              "game_version": "1.16.1", "game_mode": "survival", "world_seed": "-42",
              "start": copy.deepcopy(START), "start_world_time": 24000}
    result.update(changes)
    return result


class ArenaTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        tmp = Path(self.tempdir.name)
        self.arena = core.Arena(tmp / "data", tmp / "game")
        self.bridge = tmp / "game" / "speedrun-arena"
        (self.bridge / "results").mkdir(parents=True)
        self.state = {"session_id": SESSION, "ready": True, "active_id": None,
                      "game_version": "1.16.1", "game_mode": "survival",
                      "world_seed": "-42", "heartbeat_ms": int(time.time() * 1000),
                      "player": START}
        self.publish_state()

    def publish_state(self):
        self.state["heartbeat_ms"] = int(time.time() * 1000)
        (self.bridge / "state.json").write_text(json.dumps(self.state), encoding="utf8")

    def launch_bridge(self, **changes):
        captures = []
        def consume():
            request = self.bridge / "request.json"
            for _ in range(250):
                if request.exists():
                    break
                time.sleep(0.01)
            else:
                return
            envelope = json.loads(request.read_text(encoding="utf8"))
            captures.append(envelope)
            request.unlink()
            run_id = envelope["id"]
            result = result_for(run_id, **changes)
            dest = self.bridge / "results" / (run_id + ".json")
            tmp = dest.with_suffix(".tmp")
            tmp.write_text(json.dumps(result), encoding="utf8")
            tmp.replace(dest)
        worker = threading.Thread(target=consume)
        worker.start()
        self.addCleanup(worker.join)
        return captures

    def test_rejects_invalid_actions_instead_of_sending_them(self):
        mutations = [
            ("slots", {"slot": 9}), ("partial", {"ticks": 1.25}),
            ("bool", {"ticks": True}), ("unknown", {"keys": ["macro"]}),
            ("NaN", {"pitch": math.nan}), ("pitch", {"pitch": 91}),
            ("typo", {"ketys": ["jump"]}), ("target", {"require_target": "villager"}),
            ("repeat", {"keys": ["jump", "jump"]})]
        for case, change in mutations:
            with self.subTest(case=case):
                data = copy.deepcopy(PLAN)
                data["steps"][0].update(change)
                with self.assertRaises(core.ArenaError):
                    core.validate_plan(data)
        with self.assertRaises(core.ArenaError):
            core.validate_plan({**PLAN, "goal": {"type": "item", "item": "minecraft:", "count": 1}})
        self.assertEqual(core.validate_plan(PLAN), PLAN)

    def test_nonfinite_and_duplicate_json_input_fail_cleanly(self):
        path = Path(self.tempdir.name) / "malformed.json"
        for contents in ('{"x":1e400}', '{"agent":"first","agent":"second"}', '{"x":NaN}'):
            path.write_text(contents, encoding="utf8")
            with self.subTest(contents=contents), self.assertRaises(core.ArenaError):
                core.read_json(path)

    def test_crafting_ui_accepts_exclusive_single_entry_actions(self):
        valid_click = {"slot": 36, "button": 0, "action": "pickup"}
        steps = [{"ticks": 2, "keys": [], "ui": "inventory"},
                 {"ticks": 2, "keys": [], "click": valid_click},
                 {"ticks": 1, "keys": [], "click": {"slot": 0, "button": 1, "action": "quick_move"}},
                 {"ticks": 1, "keys": [], "ui": "close"}]
        self.assertEqual(core.validate_plan({**PLAN, "steps": steps})["steps"], steps)
        invalid = [
            {"keys": ["forward"], "ui": "inventory"}, {"ui": "creative"},
            {"ui": "close", "click": valid_click}, {"click": {**valid_click, "slot": True}},
            {"click": {**valid_click, "slot": -1}}, {"click": {**valid_click, "button": 2}},
            {"click": {**valid_click, "action": "clone"}}, {"click": {"slot": 0}},
            {"click": {**valid_click, "unrecognized": 4}}, {"click": {**valid_click, "slot": 32768}},
            {"click": valid_click, "yaw": 5}, {"ui": "inventory", "require_target": "block"}]
        for change in invalid:
            with self.subTest(change=change), self.assertRaises(core.ArenaError):
                core.validate_plan({**PLAN, "steps": [{"ticks": 1, "keys": [], **change}]})

    def test_offline_preserves_two_distinct_comparable_fixture_runs(self):
        first = self.arena.demo()
        self.arena.demo()
        reopened = core.Arena(self.arena.data_dir, self.arena.game_dir)
        rows = reopened.list_runs()
        self.assertEqual(len(rows), 2)
        self.assertEqual({r["source"] for r in rows}, {"demo"})
        self.assertEqual(len({r["comparison_key"] for r in rows}), 1)
        self.assertTrue(first[0]["comparison_key"])
        self.assertEqual(len({r["ticks"] for r in rows}), 2)
        run = reopened.get_run(first[0]["id"])
        self.assertEqual(run["trace"][-1]["tick"], run["ticks"])
        with self.assertRaises(KeyError):
            reopened.get_run("absent")

    def test_live_handshake_and_evidence_round_trip(self):
        seen = self.launch_bridge()
        record = self.arena.run(PLAN, timeout=2, ready_timeout=0, snapshot="weekly-01")
        self.assertEqual(seen[0]["session_id"], SESSION)
        self.assertGreater(seen[0]["expires_at_ms"], int(time.time() * 1000))
        self.assertEqual(seen[0]["plan"], PLAN)
        self.assertEqual(seen[0]["id"], record["id"])
        full = self.arena.get_run(record["id"])
        self.assertEqual(full["source"], "game")
        self.assertEqual(full["ticks"], 21)
        self.assertEqual(len(full["trace"]), 2)
        self.assertTrue(full["comparison_key"])
        self.assertEqual(full["world_seed"], "-42")

    def test_comparison_requires_snapshot_and_actual_matching_start(self):
        keys = []
        for snapshot, fields in [(None, {}), ("a", {}), ("a", {}),
                                 ("b", {}), ("a", {"start": {**START, "x": 1.6}}),
                                 ("a", {"world_seed": "-43"}),
                                 ("a", {"start": {"x":1.5,"y":64.0,"z":-2.5,
                                                   "dimension":"minecraft:overworld"}})]:
            self.launch_bridge(**fields)
            keys.append(self.arena.run(PLAN, timeout=2, ready_timeout=0, snapshot=snapshot)["comparison_key"])
        self.assertIsNone(keys[0])
        self.assertEqual(keys[1], keys[2])
        self.assertEqual(len(set(keys[1:6])), 4)
        self.assertIsNone(keys[-1])

    def test_practice_cohort_checks_variant_and_observed_reset_across_generations(self):
        def course(generation, variant):
            return {**START, "course_id": "asa:course_v1",
                    "course_generation": generation, "course_variant": variant}
        combos = [("course-v1-clear", course(1, 0), 12000),
                  ("course-v1-clear", course(8, 0), 13300),
                  ("course-v1-villager", course(9, 1), 15400),
                  ("course-v1-villager", course(10, 0), 15600),
                  ("course-v1-clear", course(0, 0), 11000),
                  ("course-v1-clear", START, 13000)]
        keys = []
        for snapshot, start, stamp in combos:
            generation = max(1, start.get("course_generation", 1))
            variant = start.get("course_variant", 0)
            self.state.update(course_generation=generation - 1, course_variant=variant)
            self.publish_state()
            def reset(arena, state):
                self.state.update(course_generation=generation)
                self.publish_state()
                return arena.state()
            self.launch_bridge(start=start, start_world_time=stamp)
            row = self.arena.run(PLAN, timeout=2, ready_timeout=0, snapshot=snapshot, prepare=reset)
            keys.append(row["comparison_key"])
        self.assertTrue(keys[0])
        self.assertEqual(keys[0], keys[1])
        self.assertTrue(keys[2])
        self.assertNotEqual(keys[1], keys[2])
        self.assertEqual(keys[3:], [None, None, None])
        self.launch_bridge(start=course(10, 0))
        unchecked = self.arena.run(PLAN, timeout=2, ready_timeout=0, snapshot="course-v1-clear")
        self.assertIsNone(unchecked["comparison_key"])

    def test_portal_loads_can_extend_elapsed_time_beyond_input_budget(self):
        self.launch_bridge(ticks=50, loading_ticks=8)
        row = self.arena.run(PLAN, timeout=2, ready_timeout=0)
        self.assertEqual(row["ticks"], 50)
        with self.assertRaises(core.ArenaError):
            core.validate_result(result_for(row["id"], ticks=51, loading_ticks=8), row["id"], PLAN)

    def test_discard_requires_quiescent_game_and_preserves_failure(self):
        with self.assertRaises(core.ArenaError):
            self.arena.run(PLAN, timeout=0.1, ready_timeout=0)
        with self.assertRaises(core.ArenaError):
            self.arena.recover(discard=True)
        old = time.time() - 120
        os.utime(self.bridge / "state.json", (old, old))
        row = self.arena.recover(discard=True)
        self.assertEqual(row["status"], "aborted")
        self.assertIsNone(row["ticks"])
        self.assertFalse((self.bridge / "request.json").exists())
        self.assertEqual(len(self.arena.list_runs()), 1)

    def test_prepare_serializes_reset_and_sends_expected_generation(self):
        self.state.update(course_generation=3, course_variant=0)
        self.publish_state()
        def reset(arena, state):
            with self.assertRaises(core.ArenaError):
                core.Arena(self.arena.data_dir, self.arena.game_dir).run(
                    PLAN, timeout=0.1, ready_timeout=0)
            self.state.update(course_generation=4, course_variant=0, course_id="asa:course_v1")
            self.publish_state()
            return arena.state()
        seen = self.launch_bridge()
        row = self.arena.run(PLAN, timeout=2, ready_timeout=0, prepare=reset)
        self.assertEqual(row["status"], "success")
        self.assertEqual(seen[0]["expected_course_generation"], 4)
        self.assertEqual(seen[0]["expected_course_variant"], 0)

    def test_prepare_refuses_world_or_reset_change_before_submission(self):
        for different_session in (False, True):
            self.state.update(session_id=SESSION, course_generation=2, course_variant=0)
            self.publish_state()
            def interrupted(arena, state):
                if different_session:
                    self.state["session_id"] = "c" * 32
                else:
                    self.state["course_generation"] = 9
                self.publish_state()
                return state
            with self.assertRaises(core.ArenaError):
                self.arena.run(PLAN, timeout=1, ready_timeout=0, prepare=interrupted)
            self.assertFalse((self.bridge / "request.json").exists())
            self.assertEqual(self.arena.list_runs(), [])

    def test_partial_game_evidence_stays_unranked(self):
        incomplete = dict(START)
        del incomplete["effects"]
        self.launch_bridge(start=incomplete)
        row = self.arena.run(PLAN, timeout=2, ready_timeout=0, snapshot="manual-snapshot")
        self.assertIsNone(row["comparison_key"])

    def test_settled_record_recovers_without_removing_foreign_mailbox(self):
        with self.assertRaises(core.ArenaError):
            self.arena.run(PLAN, timeout=0.1, ready_timeout=0)
        pending, = self.arena.list_runs()
        mailbox = self.bridge / "request.json"
        mailbox.write_text("unfinished foreign request", encoding="utf8")
        dest = self.bridge / "results" / (pending["id"] + ".json")
        dest.write_text(json.dumps(result_for(pending["id"])), encoding="utf8")
        self.assertEqual(self.arena.recover()["status"], "success")
        self.assertEqual(mailbox.read_text(encoding="utf8"), "unfinished foreign request")
        self.assertFalse(self.arena.pending.exists())

    def test_guarded_failure_is_kept_with_reason(self):
        self.launch_bridge(status="failed", reason="target changed: entity")
        row = self.arena.run(PLAN, timeout=2, ready_timeout=0)
        self.assertEqual(row["status"], "failed")
        self.assertIn("entity", row["reason"])

    def test_stale_state_refuses_to_enqueue(self):
        old = time.time() - 120
        os.utime(self.bridge / "state.json", (old, old))
        with self.assertRaises(core.ArenaError):
            self.arena.run(PLAN, timeout=1, ready_timeout=0)
        self.assertFalse((self.bridge / "request.json").exists())
        self.assertEqual(self.arena.list_runs(), [])

    def test_foreign_mailbox_cannot_be_overwritten(self):
        mailbox = self.bridge / "request.json"
        mailbox.write_text('{"id":"foreign"}')
        with self.assertRaises(core.ArenaError):
            self.arena.run(PLAN, timeout=1, ready_timeout=0)
        self.assertEqual(json.loads(mailbox.read_text()), {"id": "foreign"})

    def test_timeout_preserves_pending_and_can_import_late_result(self):
        with self.assertRaises(core.ArenaError):
            self.arena.run(PLAN, timeout=0.15, ready_timeout=0)
        pending, = self.arena.list_runs()
        self.assertEqual(pending["status"], "pending")
        with self.assertRaises(core.ArenaError):
            self.arena.run(PLAN, timeout=0.15, ready_timeout=0)
        request = self.bridge / "request.json"
        if request.exists():
            request.unlink()
        dest = self.bridge / "results" / (pending["id"] + ".json")
        dest.write_text(json.dumps(result_for(pending["id"])), encoding="utf8")
        self.assertEqual(self.arena.recover()["status"], "success")
        self.assertEqual(len(self.arena.list_runs()), 1)

    def test_recovery_will_not_trust_mismatched_result_ids(self):
        with self.assertRaises(core.ArenaError):
            self.arena.run(PLAN, timeout=0.15, ready_timeout=0)
        pending, = self.arena.list_runs()
        dest = self.bridge / "results" / (pending["id"] + ".json")
        dest.write_text(json.dumps(result_for(uuid.uuid4().hex)), encoding="utf8")
        with self.assertRaises(core.ArenaError):
            self.arena.recover()
        self.assertEqual(self.arena.get_run(pending["id"])["status"], "pending")

    def test_cli_demo_and_list_are_machine_readable(self):
        base = [sys.executable, "-m", "arena", "--data-dir", str(self.arena.data_dir),
                "--game-dir", str(self.arena.game_dir)]
        subprocess.run(base + ["demo"], check=True, capture_output=True)
        proc = subprocess.run(base + ["list"], check=True, capture_output=True, text=True)
        self.assertEqual({r["source"] for r in json.loads(proc.stdout)}, {"demo"})


if __name__ == "__main__":
    unittest.main()
