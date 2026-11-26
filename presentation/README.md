# Minecraft 1.16.1 本地实录

画面使用游戏自带的简体中文和原版像素字体。右上角主表按每次实际绘制更新至毫秒，另显示本段用时、已到达各节点的累计用时和同一赛道此前达到过的最快时间。初次练习没有参考成绩时显示横线。镜头一直保留游戏实际绘制的窗口、背包和片尾。

## 准备独立实例

先在已安装 Fabric Loader 0.16.14 的 Minecraft Java 1.16.1 实例退出游戏。从仓库根目录运行下列命令。Python 环境需要 Pillow。

```bash
python3 presentation/prepare.py \
  --instance /path/to/local-practice \
  --skin /path/to/your-private-skin.png \
  --route nether --rules week113-historical-v1 \
  --history-dir /path/to/private-split-history
```

通过自己的启动器选择本地练习角色 **Astra**，以 1920×1080 窗口进入存档。角色名由启动器决定。准备程序将皮肤打包至该实例的资源包目录，自动检测常见窄臂布局。可以加 `--skin-model slim` 或 `--skin-model default` 明确指定模型。皮肤覆盖仅对未开放局域网的单人世界中的本机角色生效。不同隔离实例通过同一个私有历史目录共享 PB。准备程序生成实例内的中文和窗口设置，并关闭 SpeedRunIGT 的附加计时显示。

每个全新存档的首次出生建立独立回执。计时来源为 JVM 的单调时钟，开背包和暂停的现实时间都会计入总表。进入末地、进入下界、龙血量归零和片尾通过真实客户端状态触发。主表在首次检测到通关字幕时冻结。每局开始时从先前的回执读取 PB，按实际三维生成种子、已记录的独立 RNG 种子、难度、游戏规则、路线和 `--rules` 参数分组。参考值是各节点自出生以来的最短实测累计时间；死亡前的已达节点仍可参与比较。直接进入末地的路线可以选择 `--route direct`。重新打开已有回执的存档不会建立新的全程成绩。

## 录制与帧率核验

在 Linux X11 桌面，不遮挡游戏窗口，先运行 `xwininfo -root -tree` 查找 Minecraft 的窗口 ID。FFmpeg、ffprobe 和 xwininfo 需要在同一显示会话可用。

```bash
python3 presentation/capture.py --window 0x200007 --output attempt-01.mkv
```

按 Ctrl+C 正常封装文件，也可添加 `--seconds 12` 录制固定时长。画面直接来自原生 1920×1080 客户端，按每秒 60 次取样；录制器会拒绝其他窗口尺寸。鼠标指针跟随游戏的显示状态。脚本同时保存无损的原始计时器区域帧哈希，以及测出的每秒不同画面数。持续行进、计时器正在走动的测试片段适合测量真实画面更新。计时器在片尾冻结后的静止时段不适用于这一指标。渲染机器的实际出帧能力决定最终流畅程度，输出容器的 60 fps 字段只说明播放时间基。录屏只采集视频，配乐在后期合成，避免重复压混音轨。

## 从源码构建

使用与项目其他组件相同的 Yarn 1.16.1+build.21、Fabric Loom 和 Gradle 包装器。

```bash
./mod/gradlew -p presentation remapJar
python3 presentation/prepare.py --jar presentation/build/libs/astra-local-film-0.1.0.jar \
  --instance /path/to/local-practice --skin /path/to/your-private-skin.png \
  --rules week113-historical-v1
```

## 在 Windows 或 Linux 用 ReplayMod 离线渲染

正式成片从根目录运行 `python render.py`（Windows 也可以用 `py -3 render.py`）。该入口面向已登录且能启动 Java 版的 Prism Launcher。它创建独立实例，校验原始回放、存档证据、观察日志和整包散列，再安装指定的 ReplayMod 2.6.20、Ranked 6.0.12 和本项目的画面模组。在装有 RTX 5060 的 Windows 机器上，让 Prism 的 Java 进程使用该显卡。FFmpeg 和 ffprobe 需要在 PATH 中。游戏逐帧计算 1920×1080 的 60 个时间采样，编码速度随本机硬件变化。

回放保留原始 `recording.tmcpr` 字节。世界、实体、第一人称视角和手持物由 Minecraft 与 ReplayMod 渲染。渲染程序从原局观察日志同步还原箱子、背包、工作台、鼠标位置、光标物品、快捷栏和计时。物品和界面使用原生游戏纹理，所见指针按记录位置绘制。原局没有记录的系统鼠标主题、模组弹窗、未采集的 HUD 动画无法逐像素复现。跨维度时采用原玩家当时的实体 ID。片尾以原局 `credits` 为准，计时固定在完成值，留出的片尾时长上限为原始录像的可用长度。

玩家按 F3+F 或 Shift+F3+F 调整视距时，Minecraft 会在左下角显示“[调试]： 渲染距离：…”并逐渐淡出。ReplayMod 2.6.20 通常会漏掉这条本地提示。导出器按原局键盘事件的时间和实际变更值，交给 1.16.1 的原生聊天框绘制。如果原始录像已经包含对应消息，就使用录像里的消息。仅有视距设置或路线目标的旧记录不会生成这条提示。

分段 PB 随合格局归档，只带入当时已经存在、同一赛道和同一规则的个人记录。音乐应用于本机最终 MP4。自动查找会使用原版《Sleepwalker》的本机音频、SHA-256 与第 29 周历史成片一致的视频音轨，或艺术家发行页标记为 212 秒的原版曲目。使用自己的文件时可运行 `python render.py --music "C:/Users/你的用户名/Music/Sleepwalker.flac"`。音视频合成复用原渲染视频流。原版每遍约 3:32，两遍共 7:04。背景音乐相对源轨降低 10 dB，第二遍播放至片尾。若输入录像带游戏原声，混音保留该轨原有增益。完整导出最多 25,439 帧（60 fps），从出生一直保留到结算。

对于内部校准，另存一个带视角预设的回放，选取出生后 9.4 秒起的 1.9 秒实际镜头。以下 PowerShell 命令只需在同一行中输入。

```powershell
py -3 presentation/replay_pov.py --source C:/Film/original.mcpr --journal C:/Film/observer.jsonl --output C:/Film/checked.mcpr --from-ms 9400 --length-ms 1900 --export-instance C:/Film/instance --mp4 C:/Film/chest.mp4
```

启动已安装对应版本模组的独立实例后，导出任务只执行一次。`config/astra-render-progress.json` 会每五秒成片时间写入进度，`astra-render-done.json` 表示 FFmpeg 已封装完成，`astra-render-failed.txt` 记录失败的具体原因。发生异常时原始录像仍可用于重试。可用 `ffprobe -v error -select_streams v:0 -show_entries stream=width,height,avg_frame_rate,nb_frames -of json C:/Film/chest.mp4` 查验原生尺寸、时间基及总帧数。

一次真实第 113 周采集的独立验证生成了 114 帧、1.900 秒、1920×1080 的第一人称回放。抽帧能看到原局开箱、拿走 TNT 与切回水下画面的过程。对右上角主表作二值化后，相邻 113 对帧的计时图样均有变化。
