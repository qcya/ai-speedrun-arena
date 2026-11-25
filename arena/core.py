"""Local Minecraft IPC, offline demonstration and SQLite evidence archive."""
import contextlib
import datetime as dt
import json
import math
import os
import sqlite3
import sys
import tempfile
import time
import uuid
from pathlib import Path

from .model import (MAX_PACKET, MAX_RESULT, ArenaError, comparison, encode, read_json,
                    require, validate_plan, validate_result)


def default_game_dir():
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "minecraft"
    if os.name == "nt":
        return Path(os.getenv("APPDATA") or Path.home()) / ".minecraft"
    return Path.home() / ".minecraft"


def _now():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")


def _write(path, value, *, exclusive=False):
    data = encode(value)
    require(len(data) <= MAX_PACKET + 4096, "IPC 文件超过 16 MiB 限制")
    fd, name = tempfile.mkstemp(prefix=".arena-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        if exclusive:
            try:
                os.link(name, path)
            except FileExistsError as exc:
                raise ArenaError(f"邮箱已有文件，请先核对 {path}") from exc
        else:
            os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


@contextlib.contextmanager
def _runner_lock(directory):
    """Kernel-held per-game lock. The OS releases it if the CLI process dies."""
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / ".runner.lock").open("a+b") as handle:
        try:
            if os.name == "nt":
                import msvcrt
                if handle.seek(0, os.SEEK_END) == 0:
                    handle.write(b"0")
                    handle.flush()
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise ArenaError("这个游戏目录已有控制台在操作；请等上一场结束") from exc
        yield


def _timeout(value, name, low, high):
    require(type(value) in (int, float) and math.isfinite(value) and low <= value <= high,
            f"{name} 需要为 {low}–{high} 秒的有限数")


class Arena:
    def __init__(self, data_dir=None, game_dir=None):
        self.data_dir = Path(data_dir or os.getenv("ARENA_DATA_DIR") or
                             (Path.home() / ".ai-speedrun-arena")).expanduser().resolve()
        self.game_dir = Path(game_dir or os.getenv("ARENA_GAME_DIR") or
                             default_game_dir()).expanduser().resolve()
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.db_path = self.data_dir / "runs.sqlite3"
        self.bridge = self.game_dir / "speedrun-arena"
        self.pending = self.bridge / ".runner-pending.json"
        self.request = self.bridge / "request.json"
        with self._db() as conn:
            conn.execute("""CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY, created_at TEXT NOT NULL, summary TEXT NOT NULL,
                plan TEXT NOT NULL, result TEXT NOT NULL)""")
            conn.execute("CREATE INDEX IF NOT EXISTS runs_date ON runs(created_at DESC)")

    @contextlib.contextmanager
    def _db(self):
        with contextlib.closing(sqlite3.connect(str(self.db_path), timeout=8)) as conn:
            with conn:
                yield conn

    def list_runs(self):
        with self._db() as conn:
            rows = conn.execute("SELECT summary FROM runs ORDER BY created_at DESC, id DESC")
            return [json.loads(summary) for summary, in rows]

    def get_run(self, run_id):
        with self._db() as conn:
            row = conn.execute("SELECT summary,plan,result FROM runs WHERE id=?", (run_id,)).fetchone()
        if row is None:
            raise KeyError(run_id)
        summary, plan, result = map(json.loads, row)
        return {**summary, "plan": plan, "result": result, "trace": result.get("trace", [])}

    def _save(self, meta, result, *, only_new=False, source="game"):
        plan = meta["plan"]
        snapshot = meta.get("snapshot")
        key, note = comparison(source, plan["goal"], snapshot, result, meta.get("guards"))
        summary = {"id": meta["id"], "created_at": meta["created_at"], "agent": plan["agent"],
                   "source": source, "status": result["status"], "ticks": result.get("ticks"),
                   "reason": result["reason"], "goal": plan["goal"], "world_seed": result.get("world_seed"),
                   "game_version": result.get("game_version"), "start": result.get("start"),
                   "start_world_time": result.get("start_world_time"), "session_id": meta.get("session_id"),
                   "snapshot": snapshot, "comparison_key": key, "comparison_note": note}
        statement = """INSERT INTO runs (id,created_at,summary,plan,result) VALUES (?,?,?,?,?)
                       ON CONFLICT(id) DO """
        statement += ("NOTHING" if only_new else
                      "UPDATE SET summary=excluded.summary, result=excluded.result")
        with self._db() as conn:
            conn.execute(statement, (meta["id"], meta["created_at"], encode(summary).decode(),
                                     encode(plan).decode(), encode(result).decode()))
        return summary

    def state(self):
        path = self.bridge / "state.json"
        if not path.is_file():
            return {"ready": False, "fresh": False, "arena_note": "尚未收到模组状态"}
        # Read mtime and heartbeat. A paused game can leave a seemingly ready file on disk.
        modified = path.stat().st_mtime
        state = read_json(path)
        require(isinstance(state, dict), "模组 state.json 需要 JSON 对象")
        now = time.time()
        stamp = state.get("heartbeat_ms")
        fresh = (type(stamp) is int and -2 <= now - stamp / 1000 <= 6
                 and -2 <= now - modified <= 6)
        session = state.get("session_id")
        ready = (fresh and state.get("ready") is True and not state.get("active_id")
                 and state.get("game_version") == "1.16.1" and state.get("game_mode") == "survival"
                 and isinstance(session, str) and len(session) == 32
                 and all(c in "0123456789abcdef" for c in session))
        note = ("可以运行" if ready else "心跳过期；请确认游戏在前台" if not fresh
                else "等待已打开且获得焦点的 1.16.1 单人生存世界")
        return {**state, "fresh": fresh, "ready": ready, "arena_note": note}

    def doctor(self):
        return {"python": sys.version.split()[0], "data_dir": str(self.data_dir),
                "database": str(self.db_path), "game_dir": str(self.game_dir),
                "game_dir_exists": self.game_dir.is_dir(),
                "bridge_dir": str(self.bridge), "pending": self.pending.exists(),
                "request_present": self.request.exists(), "state": self.state()}

    def _wait_ready(self, seconds, previous_session):
        until = time.monotonic() + seconds
        while True:
            state = self.state()
            if state["ready"] and state["session_id"] != previous_session:
                return state
            left = until - time.monotonic()
            if left <= 0:
                why = ("请退出再打开已恢复的地图副本" if previous_session and
                       state.get("session_id") == previous_session else state["arena_note"])
                raise ArenaError(f"未提交方案。{why}。可用 state 查看心跳及游戏目录")
            time.sleep(min(0.20, left))

    def run(self, plan, *, timeout=None, ready_timeout=45, snapshot=None, previous_session=None,
            prepare=None):
        validate_plan(plan)
        require(len(encode(plan)) < MAX_PACKET - 512, "方案编码后太大，无法写入请求邮箱")
        if snapshot is not None:
            require(isinstance(snapshot, str) and 0 < len(snapshot.strip()) <= 256
                    and all(ord(ch) >= 32 for ch in snapshot), "--snapshot 需要 1–256 个可见字符")
            snapshot = snapshot.strip()
        budget = sum(step["ticks"] for step in plan["steps"])
        if timeout is None:
            timeout = min(86400, max(30, budget * 0.075 + 30))
        _timeout(timeout, "--timeout", 0.05, 86400)
        _timeout(ready_timeout, "--ready-timeout", 0, 3600)
        require(self.game_dir.is_dir(), f"找不到游戏目录 {self.game_dir}；请设置 --game-dir")
        with _runner_lock(self.bridge):
            require(not self.pending.exists(), "上场记录仍待结算，请运行 recover")
            require(not self.request.exists(), "请求邮箱已被占用，请先检查已有请求及所属控制台")
            state = self._wait_ready(ready_timeout, previous_session)
            guards = {}
            if prepare is not None:
                session = state["session_id"]
                before = state.get("course_generation")
                prepared = prepare(self, state)
                state = self.state()
                require(isinstance(prepared, dict) and prepared.get("ready") is True and state["ready"]
                        and session == prepared.get("session_id") == state["session_id"],
                        "准备期间游戏会话、焦点或就绪状态已改变；本场未提交")
                generation = prepared.get("course_generation")
                variant = prepared.get("course_variant")
                require(type(before) is int and type(generation) is int and generation > 0
                        and generation == before + 1 and type(variant) is int
                        and variant in (0, 1) and generation == state.get("course_generation")
                        and variant == state.get("course_variant"), "练习场重置代数或布局已改变；本场未提交")
                guards = {"expected_course_generation": generation, "expected_course_variant": variant}
            run_id = uuid.uuid4().hex
            meta = {"id": run_id, "created_at": _now(), "plan": plan, "snapshot": snapshot,
                    "session_id": state["session_id"], "db": str(self.db_path), "guards": guards}
            envelope = {"id": run_id, "plan": plan, "session_id": state["session_id"], **guards,
                        "expires_at_ms": int(time.time() * 1000 + min(timeout, 15) * 1000)}
            _write(self.pending, meta, exclusive=True)
            self._save(meta, {"status": "pending", "reason": "等待游戏结果", "ticks": None})
            try:
                _write(self.request, envelope, exclusive=True)
                until = time.monotonic() + timeout
                result_path = self.bridge / "results" / (run_id + ".json")
                while not result_path.exists():
                    left = until - time.monotonic()
                    if left <= 0:
                        raise ArenaError(f"等待游戏结果超时，场次 {run_id} 已保留。"
                                         "按 Esc 停止游戏内输入，随后运行 recover 核对结果")
                    time.sleep(min(0.1, left))
                return self._settle(meta, result_path)
            except KeyboardInterrupt as exc:
                raise ArenaError(f"已中断等待 {run_id}。按 Esc 停止游戏内输入，再运行 recover") from exc

    def _settle(self, meta, path):
        result = validate_result(read_json(path, MAX_RESULT), meta["id"], meta["plan"])
        row = self._save(meta, result)
        self._clear(meta["id"])
        return row

    def _clear(self, run_id):
        try:
            envelope = read_json(self.request)
            if isinstance(envelope, dict) and envelope.get("id") == run_id:
                self.request.unlink(missing_ok=True)
        except (FileNotFoundError, ArenaError):
            # A missing, malformed or unrelated mailbox cannot be ours to delete.
            pass
        self.pending.unlink(missing_ok=True)

    def recover(self, *, discard=False):
        require(self.bridge.is_dir(), "尚无游戏通信目录")
        with _runner_lock(self.bridge):
            require(self.pending.is_file(), "没有此控制台的待结算记录")
            meta = read_json(self.pending, MAX_PACKET + 4096)
            require(isinstance(meta, dict) and meta.get("db") == str(self.db_path),
                    "该场次属于另一个数据目录，请使用原来的 --data-dir")
            run_id = meta.get("id")
            require(isinstance(run_id, str) and len(run_id) == 32 and
                    all(ch in "0123456789abcdef" for ch in run_id), "待结算 ID 无效")
            path = self.bridge / "results" / (run_id + ".json")
            if path.is_file():
                try:
                    return self._settle(meta, path)
                except ArenaError:
                    if not discard:
                        raise
            require(discard, f"场次 {run_id} 的有效结果仍未生成。按 Esc、退出世界并关闭游戏；"
                             "确认已停止后可用 recover --discard 将该场标为中止")
            require(not self.state()["fresh"], "游戏心跳仍活跃。请先关闭游戏，等待 7 秒再处理")
            row = self._save(meta, {"id": run_id, "status": "aborted", "ticks": None, "trace": [],
                                    "reason": "操作者确认已关闭游戏，放弃等待结果"})
            self._clear(run_id)
            return row

    def demo(self):
        """Two repeatable offline courses with explicitly synthetic positions/timing."""
        rows = []
        start = {"x": 0.5, "y": 64.0, "z": 0.5, "yaw": 0, "pitch": 0, "health": 20,
                 "food": 20, "dimension": "minecraft:overworld", "selected_slot": 0, "inventory": []}
        # Fixed toy speeds are deliberately independent of Minecraft physics.
        for agent, speed, keys in [("直线步行 · 演示", 0.1, ["forward"]),
                                   ("直线冲刺 · 演示", 0.15, ["forward", "sprint"])]:
            ticks = math.ceil(12 / speed)
            plan = {"version": 1, "agent": agent, "goal": {"type": "displacement", "blocks": 12},
                    "steps": [{"ticks": ticks, "keys": keys, "label": "离线直线场景"}]}
            run_id = uuid.uuid5(uuid.NAMESPACE_URL, "arena-fixture-v1/" + agent).hex
            meta = {"id": run_id, "created_at": _now(), "plan": plan, "snapshot": "fixture-v1"}
            trace = [{"tick": t, "x": 0.5, "y": 64.0, "z": round(0.5 + speed * t, 4),
                      "dimension": start["dimension"]} for t in range(0, ticks + 1, 5)]
            result = {"id": run_id, "status": "success", "reason": "离线合成轨迹达到 12 格",
                      "ticks": ticks, "trace": trace, "start": start, "world_seed": "0",
                      "game_mode": "survival", "game_version": "1.16.1", "start_world_time": 0,
                      "synthetic": True}
            self._save(meta, result, only_new=True, source="demo")
            rows.append(self.get_run(run_id))
        return rows
