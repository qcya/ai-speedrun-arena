"""Read-only, loopback-only spectator dashboard served by the Python runner."""

import ipaddress
import json
import math
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from .model import ArenaError


STATIC = Path(__file__).with_name("static")
ASSETS = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/arena.css": ("arena.css", "text/css; charset=utf-8"),
    "/arena.js": ("arena.js", "text/javascript; charset=utf-8"),
    "/flag.svg": ("flag.svg", "image/svg+xml"),
}
# The dashboard needs measurements, not raw plans or private runner configuration.
SUMMARY_FIELDS = (
    "id", "agent", "source", "status", "ticks", "reason", "goal", "created_at",
    "comparison_key", "comparison_note", "snapshot", "world_seed", "game_version",
    "start_world_time", "start",
)


def is_loopback(host):
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def summary(run):
    row = {key: run.get(key) for key in SUMMARY_FIELDS}
    start = row.get("start")
    if isinstance(start, dict):
        row["start"] = {k: start[k] for k in ("x", "y", "z", "dimension", "course_variant")
                        if k in start}
    return row


def live_sample(arena, runs):
    pending = {r["id"]: r.get("session_id") for r in runs
               if r.get("source") == "game" and r.get("status") == "pending" and r.get("session_id")}
    if not pending:
        return None
    try:
        state = arena.state()
    except (ArenaError, OSError):
        return None
    active = state.get("active_id")
    player = state.get("player")
    if (not state.get("fresh") or active not in pending or pending[active] != state.get("session_id")
            or not isinstance(player, dict) or not isinstance(player.get("dimension"), str)
            or type(state.get("tick")) is not int or state["tick"] < 0
            or not all(type(player.get(k)) in (int, float) and math.isfinite(player[k])
                       for k in ("x", "y", "z"))):
        return None
    return {"id": active, "tick": state["tick"], "step": state.get("step"),
            **{k: player[k] for k in ("x", "y", "z", "dimension")}}


def comparison_ranks(runs):
    """Competition ranks for successes with a verified comparison group."""
    groups = {}
    ranks = {}
    for row in runs:
        ticks = row.get("ticks")
        if (row.get("status") == "success" and row.get("comparison_key")
                and type(ticks) is int and ticks >= 0):
            key = row.get("source"), row["comparison_key"]
            groups.setdefault(key, []).append((ticks, row["id"]))
    for group in groups.values():
        last_ticks = None
        position = 0
        for index, (ticks, run_id) in enumerate(sorted(group), 1):
            if ticks != last_ticks:
                position = index
            ranks[run_id] = position
            last_ticks = ticks
    return ranks


def create_server(arena, host="127.0.0.1", port=8765):
    if not is_loopback(host):
        raise ValueError("观赛台仅支持回环地址 127.0.0.1、localhost 或 ::1")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            # Polling clients would otherwise fill the terminal every few seconds.
            pass

        def reply(self, status, content_type, body):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy",
                             "default-src 'self'; object-src 'none'; "
                             "base-uri 'none'; frame-ancestors 'none'")
            self.end_headers()
            if self.command != "HEAD":
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        def send_json(self, status, data):
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.reply(status, "application/json; charset=utf-8", body)

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            try:
                request_host = urlsplit("//" + self.headers.get("Host", "")).hostname
                url = urlsplit(self.path)
            except ValueError:
                self.send_json(400, {"error": "无效请求"})
                return
            if not is_loopback(request_host):
                self.send_json(403, {"error": "仅支持本机访问"})
                return
            if url.path in ASSETS:
                filename, mime = ASSETS[url.path]
                self.reply(200, mime, (STATIC / filename).read_bytes())
            elif url.path == "/api/state":
                runs = arena.list_runs()
                rows = [summary(run) for run in runs]
                ranks = comparison_ranks(rows)
                for row in rows:
                    row["rank"] = ranks.get(row["id"])
                response = {"runs": rows}
                sample = live_sample(arena, runs)
                if sample:
                    response["live"] = sample
                self.send_json(200, response)
            elif url.path == "/api/trace":
                ids = parse_qs(url.query).get("run", [])
                if len(ids) != 1 or len(ids[0]) > 256:
                    self.send_json(400, {"error": "需要一个挑战编号"})
                    return
                try:
                    run = arena.get_run(ids[0])
                except (KeyError, ValueError):
                    self.send_json(404, {"error": "未找到该挑战"})
                    return
                self.send_json(200, {"id": run["id"], "trace": run.get("trace", [])})
            else:
                self.send_json(404, {"error": "未找到该页面"})

    class Server(ThreadingHTTPServer):
        address_family = socket.AF_INET6 if ":" in host else socket.AF_INET

    return Server((host, port), Handler)


def serve(arena, host="127.0.0.1", port=8765):
    with create_server(arena, host, port) as server:
        address, bound_port = server.server_address[:2]
        label = f"[{address}]" if ":" in address else address
        print(f"本地观赛台  http://{label}:{bound_port}/", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
