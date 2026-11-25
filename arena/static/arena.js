"use strict";

const byId = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const model = {
  runs: [], selected: params.get("run"), source: null, agent: "",
  live: null, liveTrail: [], trace: [], segments: new Map(), bounds: new Map(), tick: 0, endTick: 0,
  follow: !params.get("run"), sourceLocked: ["game", "demo"].includes(params.get("source")),
  frame: 0, request: 0, obs: params.get("obs") === "1",
};
const sourceNames = { game: "实机记录", demo: "演示记录" };
const statusNames = { success: "成功", failed: "失败", aborted: "中止", pending: "待回传" };
const dimensionNames = {
  "minecraft:overworld": "主世界", "minecraft:the_nether": "下界", "minecraft:the_end": "末地",
};
if (Object.keys(sourceNames).includes(params.get("source"))) model.source = params.get("source");

function el(tag, text, classes) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (classes) node.className = classes;
  return node;
}
function appendCell(tr, content, classes) {
  const td = el("td", undefined, classes);
  td.append(content);
  tr.append(td);
}
function button(label, classes, fn) {
  const node = el("button", label, classes);
  node.type = "button";
  node.addEventListener("click", fn);
  return node;
}
function current() { return model.runs.find((r) => r.id === model.selected); }
function sourceRuns() { return model.runs.filter((r) => r.source === model.source); }
function validTicks(t) { return Number.isSafeInteger(t) && t >= 0; }
function clock(t) {
  if (!validTicks(t)) return "—";
  const cs = (t % 20) * 5;
  const sec = Math.floor(t / 20);
  const pad = (n) => String(n).padStart(2, "0");
  return pad(Math.floor(sec / 60)) + ":" + pad(sec % 60) + "." + pad(cs);
}
function dimensionName(id) { return dimensionNames[id] || id || "维度未知"; }
function goalName(g) {
  if (!g) return "目标未知";
  if (g.type === "displacement") return "位移挑战 · " + g.blocks + " 格";
  if (g.type === "dragon_exit") return "从末地返回并触发终末之诗";
  if (g.type === "item") {
    const items = { "minecraft:red_bed": "红色床", "minecraft:iron_ingot": "铁锭",
      "minecraft:diamond": "钻石", "minecraft:oak_log": "橡木原木" };
    return "获得" + (items[g.item] || " " + g.item) + " × " + (g.count ?? 1);
  }
  return g.type || "目标未知";
}
function status(r) {
  if (r.status === "pending" && r.id === model.live?.id) return el("span", "进行中", "status pending");
  const known = Object.keys(statusNames).includes(r.status);
  return el("span", known ? statusNames[r.status] : "未知状态",
    "status " + (known ? r.status : ""));
}
function stamp(iso) {
  if (!iso) return "—";
  const date = new Date(iso);
  if (!Number.isFinite(date.valueOf())) return "—";
  return date.toLocaleString("zh-CN", { year:"numeric",month:"2-digit",day:"2-digit",
    hour:"2-digit",minute:"2-digit",second:"2-digit",hour12:false });
}
function writeUrl() {
  const url = new URL(location.href);
  for (const [k, v] of [["run", model.follow ? null : model.selected], ["source", model.sourceLocked ? model.source : null], ["obs", model.obs ? "1" : null]]) {
    if (v) url.searchParams.set(k, v);
    else url.searchParams.delete(k);
  }
  history.replaceState({}, "", url);
}
function setSourceBadge(id) {
  const node = byId(id);
  node.textContent = sourceNames[model.source] || "等待记录";
  node.classList.remove("demo", "game");
  if (model.source) node.classList.add(model.source);
}
function showSources() {
  for (const control of document.querySelectorAll("[data-source]")) {
    const source = control.dataset.source;
    byId("count-" + source).textContent = model.runs.filter((r) => r.source === source).length;
    control.setAttribute("aria-pressed", String(source === model.source || (!model.source && source === "game")));
  }
  setSourceBadge("obs-source");
}
function renderScores(run) {
  const verified = Boolean(run.comparison_key);
  const group = verified
    ? sourceRuns().filter((r) => r.comparison_key === run.comparison_key)
    : [run];
  group.sort((a, b) => (a.rank ?? Infinity) - (b.rank ?? Infinity));
  byId("goal").textContent = goalName(run.goal);
  setSourceBadge("source-tag");
  const best = group.find((r) => r.rank === 1);
  const display = verified ? best : run;
  const active = !verified && model.live?.id === run.id;
  const ticks = active ? model.live.tick : display?.ticks;
  byId("score-label").textContent = verified ? "组内最快" : active ? "进行中 · 游戏用时" : "本次游戏用时";
  byId("best-time").textContent = clock(ticks);
  const tied = best ? group.filter((r) => r.rank === 1).length : 0;
  byId("best-info").textContent = display
    ? [display.agent, validTicks(ticks) ? ticks + " tick" : "", tied > 1 ? "该成绩出现 " + tied + " 次" : ""].filter(Boolean).join("  ·  ")
    : "此组尚无成功记录";
  const body = byId("rank-rows");
  body.replaceChildren();
  const agents = new Map();
  for (const row of group) {
    if (!agents.has(row.agent)) agents.set(row.agent, {row, count:0, successes:0});
    const entry = agents.get(row.agent);
    entry.count++;
    if (row.status === "success") entry.successes++;
  }
  let index = 0, place = 0, previousTicks = null;
  for (const {row, count, successes} of agents.values()) {
    index++;
    if (row.rank && row.ticks !== previousTicks) place = index;
    previousTicks = row.ticks;
    const tr = el("tr", undefined, row.rank && place === 1 ? "top" : "");
    appendCell(tr, row.rank ? String(place).padStart(2, "0") : "—");
    const agent = button(row.agent || "未命名智能体", "link-button", () => chooseRun(row.id));
    agent.setAttribute("aria-label", "查看 " + agent.textContent + "，" + stamp(row.created_at));
    appendCell(tr, agent);
    appendCell(tr, successes + " / " + count);
    appendCell(tr, row.status === "success" ? clock(row.ticks) : status(row), "time");
    body.append(tr);
  }
  byId("comparison").textContent = !verified ? "本条记录单独展示，缺少可比的起始条件。"
    : model.source === "demo" ? "演示组使用合成轨迹，成绩仅供试播。"
    : (run.comparison_note || "已按相同赛制与起始条件分组。");
}
function reasonName(reason) {
  const names = {
    goal_reached: "已达到挑战目标", end_exit_credits: "已进入末地返回传送门并触发终末之诗",
    plan_exhausted: "所有动作已执行完毕，仍未达成目标",
    player_died: "玩家死亡", window_lost_focus: "游戏窗口失去焦点",
    menu_or_pause: "打开菜单或暂停了游戏", world_unavailable: "世界或玩家数据已不可用",
    dimension_load_timeout: "切换维度超时", survival_required: "当前模式不符合生存挑战要求",
    private_singleplayer_world_required: "需要在本地单人世界中运行",
    not_private_singleplayer: "已离开本地单人环境", stale_session: "游戏世界已切换",
    expired_request: "请求已过期", course_generation_changed: "场地重置轮次已改变",
    course_variant_changed: "布局已改变", goal_already_met_at_start: "开始时已持有目标物品",
    bridge_error: "控制模组未能继续执行", client_stopped: "游戏已关闭",
  };
  const guard = /^target_guard_expected_(block|entity|miss)_saw_(block|entity|miss)$/.exec(reason);
  if (guard) {
    const targets = {block:"方块", entity:"实体", miss:"空处"};
    return "动作要求准星指向" + targets[guard[1]] + "，实际指向" + targets[guard[2]] + "。";
  }
  return Object.keys(names).includes(reason) ? names[reason] : reason;
}
function renderDetails(run) {
  const list = byId("detail-list");
  list.replaceChildren();
  const start = run.start || {};
  const active = model.live?.id === run.id ? model.live : null;
  const entries = [
    ["智能体", run.agent], ["游戏刻", active ? active.tick + " tick" : validTicks(run.ticks) ? run.ticks + " tick" : "—"],
    ["当前动作", active && Number.isInteger(active.step) ? "第 " + (active.step + 1) + " 步" : null],
    ["场地布局", start.course_variant === 0 ? "空场" : start.course_variant === 1 ? "村民挡路" : null],
    ["版本", run.game_version], ["世界种子", run.world_seed],
    ["起点快照", run.snapshot], ["起始世界时间", run.start_world_time],
    ["起点坐标", [start.x,start.y,start.z].every(Number.isFinite) ? [start.x,start.y,start.z].map((n) => n.toFixed(1)).join(" / ") : null],
    ["挑战编号", run.id],
  ];
  for (const [key, value] of entries) {
    if (value === null || value === undefined || value === "") continue;
    const wrapper = el("div");
    wrapper.append(el("dt", key), el("dd", String(value)));
    list.append(wrapper);
  }
  byId("detail-status").replaceChildren(active ? el("span", "进行中", "status pending") : status(run));
  byId("reason").hidden = !run.reason;
  byId("reason").className = ["aborted", "failed"].includes(run.status) ? "caution" : "";
  byId("reason").textContent = run.reason ? "结果说明　" + reasonName(run.reason) : "";
  byId("reason").title = run.reason || "";
}
function renderHistory() {
  const rows = sourceRuns();
  const filter = byId("agent-filter");
  const agents = [...new Set(rows.map((r) => r.agent))].sort();
  if (!agents.includes(model.agent)) model.agent = "";
  filter.replaceChildren(new Option("全部智能体", ""));
  for (const agent of agents) filter.add(new Option(agent, agent));
  filter.value = model.agent;
  const body = byId("history-rows");
  body.replaceChildren();
  const visible = rows.filter((r) => !model.agent || r.agent === model.agent);
  byId("history-count").textContent = visible.length;
  for (const r of visible) {
    const active = r.id === model.selected;
    const tr = el("tr", undefined, active ? "current" : "");
    appendCell(tr, button(r.agent || "未命名智能体", "link-button", () => chooseRun(r.id)));
    appendCell(tr, goalName(r.goal));
    appendCell(tr, status(r));
    appendCell(tr, clock(r.ticks), "time");
    appendCell(tr, stamp(r.created_at), "time");
    const inspect = button(active ? "已选中" : "查看 ↗", "replay-link", () => chooseRun(r.id));
    inspect.setAttribute("aria-label", "查看挑战 " + r.id);
    if (active) inspect.setAttribute("aria-current", "true");
    appendCell(tr, inspect);
    body.append(tr);
  }
}
function showRun() {
  showSources();
  byId("follow").setAttribute("aria-pressed", String(model.follow));
  const r = current();
  byId("workspace").hidden = !r;
  byId("empty").hidden = Boolean(r);
  if (!r) {
    byId("empty-title").textContent = model.source === "demo" ? "暂无演示记录" : "暂无实机记录";
    byId("empty-text").textContent = model.source === "demo" || !model.runs.length
      ? "要预览演示回放，请在项目目录运行下面的命令。观赛台会自动更新。"
      : "本机游戏回传挑战后，成绩与坐标将自动出现在这里。";
    byId("empty-command").hidden = Boolean(model.runs.length) && model.source !== "demo";
    return;
  }
  renderScores(r);
  renderDetails(r);
  renderHistory();
  byId("trace-agent").textContent = r.agent;
  byId("trace-title").textContent = r.status === "pending" ? "实时位置" : "轨迹回放";
}
function chooseRun(id, follow = false) {
  model.follow = follow;
  const next = model.runs.find((r) => r.id === id);
  if (next && model.agent && next.agent !== model.agent) model.agent = "";
  model.selected = next?.id || null;
  if (next) model.source = next.source;
  writeUrl();
  showRun();
  loadTrace();
}
for (const control of document.querySelectorAll("[data-source]")) {
  control.addEventListener("click", () => {
    model.source = control.dataset.source;
    model.sourceLocked = true;
    model.agent = "";
    chooseRun(sourceRuns()[0]?.id, true);
  });
}
byId("agent-filter").addEventListener("change", (e) => {
  model.agent = e.target.value;
  const selected = current();
  if (model.agent && model.agent !== selected?.agent) {
    chooseRun(sourceRuns().find((r) => r.agent === model.agent)?.id, model.follow);
  } else renderHistory();
});
byId("follow").addEventListener("click", () => {
  if (model.follow) {
    model.follow = false;
    byId("follow").setAttribute("aria-pressed", "false");
    writeUrl();
  } else {
    chooseRun(sourceRuns().find((r) => !model.agent || r.agent === model.agent)?.id, true);
  }
});
function broadcast() {
  document.body.classList.toggle("obs", model.obs);
  byId("broadcast").setAttribute("aria-pressed", String(model.obs));
  byId("broadcast-label").textContent = model.obs ? "退出转播" : "转播视图";
}
byId("broadcast").addEventListener("click", () => { model.obs = !model.obs; broadcast(); writeUrl(); });
broadcast();

async function fetchJson(url) {
  const response = await fetch(url, {cache: "no-store"});
  if (!response.ok) throw Error("HTTP " + response.status);
  return response.json();
}
let previous = null;
async function refresh() {
  try {
    const {runs, live} = await fetchJson("/api/state");
    const priorLive = model.live;
    model.live = live || null;
    if (model.live && priorLive?.id !== live.id) model.liveTrail = [];
    if (live && live.tick !== model.liveTrail[model.liveTrail.length - 1]?.tick) {
      model.liveTrail.push(live);
      if (model.liveTrail.length > 150) model.liveTrail.shift();
    }
    if (!live) model.liveTrail = [];
    const signature = JSON.stringify(runs);
    byId("connection").className = "ok";
    byId("connection").lastElementChild.textContent = "本地已连接";
    byId("message").hidden = true;
    if (signature !== previous) {
      const formerly = current();
      model.runs = runs;
      if (model.follow && !model.sourceLocked && runs.length) {
        model.source = runs.some((r) => r.source === "game") ? "game" : "demo";
      }
      let next = model.follow ? sourceRuns().find((r) => !model.agent || r.agent === model.agent) : current();
      if (model.source && next?.source !== model.source) next = null;
      if (!next && !model.source && runs.length) model.source = runs.some((r) => r.source === "game") ? "game" : "demo";
      if (!next) next = sourceRuns()[0];
      const needsTrace = previous === null || next?.id !== formerly?.id || formerly?.status !== next?.status;
      model.selected = next?.id || null;
      if (next) model.source = next.source;
      showRun();
      if (needsTrace) loadTrace();
      previous = signature;
    }
    if (current()?.status === "pending" && (live?.id === model.selected || priorLive?.id === model.selected)) {
      renderScores(current());
      renderDetails(current());
      loadTrace();
    }
  } catch {
    byId("connection").className = "offline";
    byId("connection").lastElementChild.textContent = "连接中断";
    byId("message").hidden = false;
    byId("message").textContent = "暂时无法读取本地观赛台。请确认服务仍在运行，页面会自动重连。";
  } finally { setTimeout(refresh, model.live ? 750 : 2500); }
}

function setPlaying(playing) {
  byId("play").setAttribute("aria-label", playing ? "暂停轨迹" : "播放轨迹");
  byId("play").firstElementChild.className = playing ? "pause-shape" : "play-shape";
}
function stop() {
  cancelAnimationFrame(model.frame);
  model.frame = 0;
  setPlaying(false);
}
function indexTrace(raw) {
  model.trace = [];
  model.segments = new Map();
  model.bounds = new Map();
  let last = null;
  for (const p of raw) {
    if (!p || !validTicks(p.tick) || ![p.x, p.y, p.z].every(Number.isFinite) || !p.dimension
        || (model.trace.length && p.tick < model.trace[model.trace.length - 1].tick)) {
      last = null;
      continue;
    }
    model.trace.push(p);
    if (!model.segments.has(p.dimension)) {
      model.segments.set(p.dimension, []);
      model.bounds.set(p.dimension, {minX:p.x, maxX:p.x, minZ:p.z, maxZ:p.z});
    }
    const segments = model.segments.get(p.dimension);
    if (last !== p.dimension) segments.push([]);
    segments[segments.length-1].push(p);
    const b = model.bounds.get(p.dimension);
    b.minX = Math.min(b.minX, p.x); b.maxX = Math.max(b.maxX, p.x);
    b.minZ = Math.min(b.minZ, p.z); b.maxZ = Math.max(b.maxZ, p.z);
    last = p.dimension;
  }
  const dims = byId("dimension");
  dims.replaceChildren(new Option("跟随轨迹维度", ""));
  for (const dim of model.segments.keys()) dims.add(new Option(dimensionName(dim), dim));
  byId("dimension-label").hidden = model.segments.size < 2;
}
function showTrace(raw, label) {
  const run = current();
  indexTrace(Array.isArray(raw) ? raw : []);
  const last = model.trace[model.trace.length - 1];
  model.endTick = last ? Math.max(last.tick, validTicks(run?.ticks) ? run.ticks : 0) : 0;
  model.tick = model.endTick;
  byId("scrub").max = Math.max(model.endTick, 1);
  byId("scrub").value = model.tick;
  byId("scrub").disabled = !model.endTick;
  byId("play").disabled = !model.endTick;
  byId("map-empty").textContent = label || "本次挑战没有可回放的坐标";
  byId("map-empty").hidden = Boolean(last);
  byId("map-key").hidden = !last;
  byId("total-tick").textContent = model.endTick;
  draw();
}
async function loadTrace() {
  const request = ++model.request;
  stop();
  showTrace([], "读取坐标中…");
  if (!model.selected) return;
  if (current()?.status === "pending") {
    if (model.live?.id === model.selected) {
      showTrace(model.liveTrail);
      byId("play").disabled = true;
      byId("scrub").disabled = true;
      byId("map-key").hidden = true;

    } else showTrace([], "等待本机游戏回传坐标");
    return;
  }
  try {
    const data = await fetchJson("/api/trace?run=" + encodeURIComponent(model.selected));
    if (request === model.request) showTrace(data.trace);
  } catch {
    if (request === model.request) {
      showTrace([], "暂时无法读取轨迹，正在重试…");
      setTimeout(() => { if (request === model.request) loadTrace(); }, 3500);
    }
  }
}
function lastSample(tick) {
  let lo = 0, hi = model.trace.length;
  while (lo < hi) {
    const mid = Math.floor((lo + hi) / 2);
    if (model.trace[mid].tick <= tick) lo = mid + 1;
    else hi = mid;
  }
  return model.trace[lo-1];
}
function gridStep(target) {
  const power = 10 ** Math.floor(Math.log10(target));
  return ([1, 2, 5, 10].find((x) => x * power >= target) || 10) * power;
}
function draw() {
  const cvs = byId("map");
  const w = cvs.clientWidth, h = cvs.clientHeight;
  const p = lastSample(model.tick);
  const dim = byId("dimension").value || p?.dimension || model.trace[0]?.dimension;
  const shown = p?.dimension === dim ? p : null;
  byId("current-tick").textContent = model.tick;
  byId("scrub").value = model.tick;
  byId("map-dimension").textContent = dim ? dimensionName(dim) + (model.live?.id === model.selected && model.live ? " · 最近实时采样" : "") : "";
  byId("position").textContent = shown ? "X " + shown.x.toFixed(1) + "   Y " + shown.y.toFixed(1) + "   Z " + shown.z.toFixed(1) : "—";
  if (!w || !h) return;
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  if (cvs.width !== Math.round(w*ratio) || cvs.height !== Math.round(h*ratio)) {
    cvs.width = Math.round(w*ratio); cvs.height = Math.round(h*ratio);
  }
  const ctx = cvs.getContext("2d");
  if (!ctx) return;
  ctx.setTransform(ratio,0,0,ratio,0,0);
  ctx.clearRect(0,0,w,h);
  const box = model.bounds.get(dim);
  if (!box) return;
  const xMid = (box.minX + box.maxX) / 2, zMid = (box.minZ + box.maxZ) / 2;
  const scale = Math.min((w-76)/Math.max(4, box.maxX-box.minX), (h-66)/Math.max(4, box.maxZ-box.minZ));
  const X = (x) => w/2+(x-xMid)*scale;
  const Z = (z) => h/2+(z-zMid)*scale;
  if (!Number.isFinite(scale) || scale <= 0) return;
  const step = gridStep(52/scale);
  ctx.font = '10px Consolas, "Liberation Mono", monospace';
  ctx.strokeStyle="#293426"; ctx.lineWidth=1; ctx.fillStyle="#899780";
  const xMin = xMid-w/(2*scale), zMin = zMid-h/(2*scale);
  for (let i=0; i<=Math.ceil(w/52)+1; i++) {
    const n=Math.ceil(xMin/step)+i;
    const x=X(n*step); ctx.beginPath(); ctx.moveTo(x,0); ctx.lineTo(x,h); ctx.stroke();
    if(x>28 && x<w-49) ctx.fillText(String(Number((n*step).toFixed(2))),x+3,h-6);
  }
  for (let i=0; i<=Math.ceil(h/52)+1; i++) {
    const n=Math.ceil(zMin/step)+i;
    const y=Z(n*step); ctx.beginPath(); ctx.moveTo(0,y); ctx.lineTo(w,y); ctx.stroke();
    if(y>32 && y<h-19) ctx.fillText(String(Number((n*step).toFixed(2))),5,y-3);
  }
  ctx.fillText("Z ↓",6,13); ctx.fillText("X →",w-25,h-6);
  const segments = model.segments.get(dim);
  function path(complete) {
    ctx.strokeStyle=complete ? "#526748" : "#b8f360"; ctx.lineWidth=complete ? 2 : 2.8;
    ctx.lineJoin="round"; ctx.lineCap="round";
    for (const segment of segments) {
      ctx.beginPath();
      for (let i=0; i<segment.length; i++) {
        const q=segment[i];
        if (!complete && q.tick>model.tick) break;
        if (i===0) ctx.moveTo(X(q.x),Z(q.z));
        else ctx.lineTo(X(q.x),Z(q.z));
      }
      ctx.stroke();
    }
  }
  path(true); path(false);
  const first=model.trace[0];
  if (first?.dimension === dim) {
    ctx.fillStyle="#111710"; ctx.strokeStyle="#acc19b"; ctx.lineWidth=1.5;
    ctx.fillRect(X(first.x)-3.5,Z(first.z)-3.5,7,7); ctx.strokeRect(X(first.x)-3.5,Z(first.z)-3.5,7,7);
  }
  if (shown) {
    const x=X(shown.x),y=Z(shown.z);
    ctx.beginPath();ctx.arc(x,y,9,0,2*Math.PI);ctx.fillStyle="#b8f36030";ctx.fill();
    ctx.beginPath();ctx.arc(x,y,4,0,2*Math.PI);ctx.fillStyle="#b8f360";ctx.fill();
  }
}
byId("dimension").addEventListener("change", draw);
byId("scrub").addEventListener("input", (event) => { stop(); model.tick=Number(event.target.value); draw(); });
byId("play").addEventListener("click", () => {
  if (model.frame) { stop(); return; }
  if (model.tick >= model.endTick) model.tick=0;
  const base=model.tick, start=performance.now();
  setPlaying(true);
  draw();
  function frame(now) {
    const next=Math.min(model.endTick,base+Math.floor((now-start)/50));
    if (next !== model.tick) { model.tick=next; draw(); }
    if (next < model.endTick) model.frame=requestAnimationFrame(frame);
    else stop();
  }
  model.frame=requestAnimationFrame(frame);
});
new ResizeObserver(draw).observe(byId("map"));
refresh();
