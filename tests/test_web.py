import json
import threading
import unittest
from tempfile import TemporaryDirectory
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from arena.core import Arena
from arena.web import comparison_ranks, create_server, live_sample


class DashboardTests(unittest.TestCase):
    def setUp(self):
        tmp = TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.arena = Arena(tmp.name)
        self.server = create_server(self.arena, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={"poll_interval": .01}, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.url = "http://127.0.0.1:" + str(self.server.server_port)

    def stop_server(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def read_json(self, path):
        with urlopen(self.url + path, timeout=3) as response:
            return json.load(response)

    def test_empty_and_demo(self):
        self.assertEqual(self.read_json("/api/state"), {"runs": []})
        self.arena.demo()
        runs = self.read_json("/api/state")["runs"]
        self.assertEqual(len(runs), 2)
        self.assertEqual({r["source"] for r in runs}, {"demo"})
        ordered = sorted(runs, key=lambda r: r["rank"])
        self.assertLess(ordered[0]["ticks"], ordered[1]["ticks"])
        self.assertNotIn("trace", ordered[0])
        for r in runs:
            replay = self.read_json("/api/trace?run=" + r["id"])
            self.assertEqual(replay["trace"], self.arena.get_run(r["id"])["trace"])
        with urlopen(self.url + "/?obs=1") as html:
            self.assertIn("AI 速通竞技场", html.read().decode())
        for asset in ("/arena.css", "/arena.js", "/flag.svg"):
            with urlopen(self.url + asset) as resource:
                self.assertEqual(resource.status, 200)

    def test_ranks_never_cross_sources_or_groups(self):
        def row(rid, ticks, source="game", group="A", status="success"):
            return dict(id=rid, ticks=ticks, source=source, comparison_key=group, status=status)
        runs = [row("one", 30), row("two", 30), row("three", 31), row("failed", 1, status="failed"),
                row("pending", None, status="pending"), row("demo", 1, source="demo"),
                row("other", 80, group="B"), row("unverified", 20, group=None)]
        self.assertEqual(comparison_ranks(runs),
                         {"one": 1, "two": 1, "three": 3, "demo": 1, "other": 1})

    def test_live_heartbeat_must_belong_to_pending_real_run(self):
        runs = [dict(id="run-a", source="game", status="pending", session_id="world-a")]
        state = dict(active_id="run-a", session_id="world-a", fresh=True, tick=7, step=0,
                     player=dict(x=0., y=64., z=3., dimension="minecraft:overworld"))
        self.arena.state = lambda: state
        self.assertEqual(live_sample(self.arena, runs)["tick"], 7)
        for fields in ({"fresh": False}, {"active_id": "other"}, {"session_id": "old"},
                       {"player": {}}, {"tick": -1}):
            with self.subTest(fields=fields):
                self.arena.state = lambda: {**state, **fields}
                self.assertIsNone(live_sample(self.arena, runs))
        self.arena.state = lambda: state
        for fields in ({"status": "success"}, {"source": "demo"}):
            self.assertIsNone(live_sample(self.arena, [{**runs[0], **fields}]))

    def test_readonly_local_service(self):
        with self.assertRaises(ValueError):
            create_server(self.arena, "0.0.0.0", 0)
        for route, code in [("/api/trace?run=missing", 404), ("/api/trace", 400),
                            ("/../web.py", 404), ("/runs.sqlite3", 404)]:
            with self.subTest(path=route), self.assertRaises(HTTPError) as caught:
                self.read_json(route)
            self.assertEqual(caught.exception.code, code)
        request = Request(self.url + "/api/state", headers={"Host": "other.invalid"})
        with self.assertRaises(HTTPError) as caught:
            urlopen(request)
        self.assertEqual(caught.exception.code, 403)
        with self.assertRaises(HTTPError) as caught:
            urlopen(Request(self.url + "/api/state", data=b"{}"))
        self.assertEqual(caught.exception.code, 501)
