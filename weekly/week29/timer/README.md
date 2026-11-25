# 练习局双计时器

右上角显示从出生起算的总用时、当前一段已花的时间，以及各分段从出生算起的本局用时和历史最快值。先前到达过某个分段的失败场次也可贡献该段记录。画面只显示本局实际经过的分段。到达原版终末之诗后两只计时器一同停止。

## 原速成片

安装 FFmpeg（需要 libass 和 libx264）、Noto Sans CJK SC 和 DejaVu Sans Mono。在仓库根目录执行，路径替换为同一局的原始录屏和游戏观察日志。`.startms` 是采集器开始录制时记录的 Unix 毫秒；相机必须从出生前一直录到终幕。输出保留原始分辨率和帧率，录屏和观察器应使用同一系统时钟。传入上一局或多局更早的同种子、同规则、同路线类型的实际日志即可显示历史对照；无历史时显示横线。

```bash
python3 weekly/week29/timer/split_timer.py render \
  --raw take.mkv --startms take.startms --log saves/Week29/observed-run.jsonl \
  --reference weekly/week29/evidence/fixed-route/observed-run.jsonl.gz \
  --limit 600 --music /path/to/Sleepwalker.flac --out week29-practice.mp4
```

`--limit 600` 仅允许出生到终幕严格小于 600 秒的完整通关记录。收尾默认保留 5 秒，也可用 `--tail` 设置。提供配乐时，音频从头顺播一遍，若要用同一首循环可加 `--repeat-music`。不提供配乐时保留录屏原音。查看历史录屏局部时可用 `--from-seconds 950 --length 24`；总用时仍显示这局真实进度。

## 自动筛局

将本目录的 `practice.example.json` 复制到同目录，修改 `launch`，填入**本机已有的 Minecraft 启动适配器**的可执行文件和参数。路径中的 `world_template`、`mod` 和 `itinerary` 相对于配置文件，例子按本项目第 29 周客户端布局编写。适配器应将 `{game_dir}` 作为独立游戏目录，加载新世界 `{world_name}` 并在前台持续运行，整个 MC 实例归这个进程组管理。也可自行启动相同分辨率、固定帧率的录屏并将录像、开始时间写入 `{attempt_dir}`。平台不会启动一个已经运行的共享游戏进程。适配器连接现有本地 Java 版客户端所需的运行环境、登录和录屏参数由本机负责。

```bash
python3 weekly/week29/timer/practice_attempts.py \
  --config weekly/week29/timer/my-practice.json --out /tmp/week29-trials \
  --reference-evidence weekly/week29/evidence/fixed-route
```

每局单独建立目录，复制预设世界、模组和路线，再开始计时。首次到达分段后，记录该段成绩供下一局使用。开启某个 `gates` 后，一旦游戏样本确认这一局超出此前该分段最快值和 `grace_ms` 的和，就结束这一局并新开存档；缺少此前成绩时先积累样本。`route: nether` 的路线可使用 `enter_nether`，直达末地的路线可使用 `enter_end`。超时或死亡也会重开。每局保留日志、重开原因和比较基准。最多运行 `max_attempts` 次。启动错误保留诊断并退出。

可重复提供 `--reference-evidence` 指向同类练习场次的证据包。程序检查原版地形种子、世界版本、生存模式、作弊开关、初始模板哈希和实际路线类型。练习批次的成绩按配置中的课程、三界种子、初始模板、规则和路线类型归组。各分段的基准可以来自不同的历史场次。

如需在原版终幕后保存录像，设置可选的 `"finalize": ["/absolute/path/to/save-recording", "{attempt_dir}", "{game_dir}"]`。该指令在游戏结束进程前执行。回调和启动命令均接收独立的参数数组，支持 `{attempt_dir}`、`{attempt_id}`、`{game_dir}`、`{world_dir}`、`{world_name}`、`{log}` 和 `{result}`。每轮只向该轮单独创建的进程组发送退出信号。返回码 0 表示已得到通关，10 表示本批次的尝试用完，2 表示环境或启动故障。

若本机已经有负责重开的外层程序，也可独立运行 `split_timer.py watch --log <新局日志> --route direct --reference <旧局日志> --gate enter_end --limit 600`。程序用 JSON 写出进度，返回 10 时通知外层重开。
