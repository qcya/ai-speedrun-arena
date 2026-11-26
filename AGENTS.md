# 在本机导出 Astra 的速通录像

第 68 局已定稿，原生通关成绩 **6:59.485**。本分支为这局提供原生 4K60 导出。将该局的 `Astra-W113.zip` 放入 `weekly/week113/release/`，然后运行 `python render.py --check` 和 `python render.py --resolution 2160p`。[原局校验值、片长与声音处理](weekly/week113/release/E68.md)

要处理第 113 周，先从仓库根目录运行 `python render.py --check`。校验通过后运行 `python render.py`。默认成片是当前目录的 `Astra-W113-1080p60.mp4`。导出使用随完整场次封存的回放和有序事件，以原速渲染出生、物品栏、实体、龙战和结算。Windows 上使用已有 Minecraft Java 账号的 Prism Launcher、Python 3 及可从 PATH 调用的 FFmpeg 和 FFprobe。先检测这些程序和现有启动器的数据目录。若缺少程序，可通过系统的应用安装器安装 PrismLauncher.PrismLauncher 或 Gyan.FFmpeg，然后重新检查。账号选择由本机启动器处理。

默认程序读取 `weekly/week113/release/Astra-W113.zip`。若缺少完整场次，本次请求只要求重现已经录下的同一局，就直接报告该版本的发布状态。如果用户也要求在本机把挑战跑完、达标并出片，或把项目链接交给你按本项目目标执行，请立即转入下方本机训练流程。校验若发现已有文件受损，应先重新获取仓库中的原件。训练片段、历史第 29 周的成片和夏末的官方回放各有独立用途；判定本周成绩时使用 Astra 本人的完整原生计时。夏末（xiamoys）的 5:35.528 作为分段参考。Astra 的原生 SpeedRunIGT 须短于七分钟，原速成片从出生录到结算，时长须短于两遍配乐的 7:04。这是本地单人练习，发布地点仅为本仓库。

若成功，确认最终 MP4 存在并与每秒 60 帧的帧数相符，然后向用户提供本机成片路径。录制者皮肤、分段和已验证的乐曲来源由程序读取。原版《Sleepwalker》约 3:32，播放至多两遍，配乐相对音源降低 10 dB。用户另外指定音乐文件时可以传 `--music`。

本机挑战可用 `python practice.py --attempts 3 --output Astra-W113-1080p60.mp4` 分批运行。程序返回 2 表示这一批尚未达标。Codex 应读取工作目录 `receipts` 中的最后位置、动作及与夏末的同进度 IGT 差值。记录中的 `comparison_ormo_ms` 也可用来研究 Ormo 哪些分段更快。如果多局卡在同一动作，查对应存档的 `astra-run.jsonl`，对照 `weekly/week113/champion.json` 修正控制器，再用正常出生的新局复验。Windows 构建命令为 `mod\gradlew.bat -p weekly/week113 remapJar`，Linux 为 `bash mod/gradlew -p weekly/week113 remapJar`。将构建出的 `weekly/week113/build/libs/astra-week113-0.1.0.jar` 复制为 `weekly/week113/astra-week113.jar` 后再启动下一批。持续到程序封存达标场次并导出视频，或出现需要用户处理的启动器登录等具体阻碍。它会在独立的单人存档中反复实跑，正常保存每一局。在第 113 周原始地图上取得符合七分钟时限的完整场次后，它封存那一局，再调用同一套导出程序。仓库包含原始 Astra 瘦臂皮肤。Windows 电脑上的正版账号由既有 Prism Launcher 管理。仅要求导出现有好成绩时，以已经封存的同一局为准。训练参数见 [本机实跑说明](weekly/week113/PRACTICE.md)。
