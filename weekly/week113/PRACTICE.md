# 在本机自动练第 113 周

已有合格录像时，在仓库根目录运行 `python render.py` 就能导出原场次。如果你希望让 Astra 亲自重新跑图，在同一目录运行下面的命令。每次都会创建一个新的 Prism 实例和单人生存世界。

```powershell
python practice.py
```

所需程序是 Python 3.10 或以上、Pillow、Prism Launcher 10 或以上、可运行 Minecraft Java 版的本机正版账号，以及能从终端调用的 FFmpeg 和 FFprobe。先在 Prism 中完成账号与 Java 配置。在本机缺少 Pillow 时可运行 `python -m pip install Pillow`。脚本通过 Prism 的 `--offline Astra` 在单机世界使用 Astra 这个名字。启动器按自己的正常流程管理游戏运行文件和账号。[Prism 命令行实现](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/Application.cpp)

仓库内已附 Astra 使用的原始 64×64 瘦臂皮肤，程序会自动读取。指定其他本地文件可用 `--skin`。给音乐传入本机的原版《Sleepwalker》文件，就能在自动导出时使用你选的版本。

```powershell
python practice.py --skin "D:\MC\qcya_ovo.png" --music "D:\Music\Sleepwalker.mp3"
```

程序在每次启动前校验本周世界模板、路由模组和原生录制模组。游戏从出生开始写行为日志，ReplayMod 同时录制原始数据包。角色死亡或错过主动开启的分段门槛时，程序请求该次游戏正常关闭，等 Minecraft 写完存档和回放，再开新世界。每次尝试都保留在自己的 Prism 实例中，可以逐一检查。长时间训练会持续占用磁盘空间。

完整场次还须有下界第三次投珠、用快捷键将视距从 16 降至 2、收齐六根烈焰棒后恢复到 16，以及瞬间传回高台的逐刻记录。角色进入结算后，程序保留 18 秒原始结算画面，校验 SpeedRunIGT、完整路线、地图、录像起止时间及文件完整性。成功场次的原生游戏计时必须小于夏末在本图的 335,528 毫秒。5:35.527 达标，5:35.528 平局，继续训练。实际连续时长随回放保存。固定目标的校验在本机完成，周榜换图后仍可复核。未达标的场次只留下练习记录。取得合格成绩所需的尝试次数取决于路线和游戏内实际发生的事件。

本机新局的初始视距为 16 个区块。带盾路线会为盾牌和打火石各留一块铁锭。有余铁就做铁铲，铁锭不足时做木铲，宝箱里的金锭留给猪灵。连续训练默认交替尝试铁质压力板快开和木质压力板带盾两种开局，重启程序会接着上一次的选择。要固定一个策略，在命令末尾加 `--opening fast` 或 `--opening shield`。另可使用 `--opening east` 走东侧峡谷。Astra 会尝试用船接住洞穴里的末影人，取得的洞穴珍珠会带过下界入口，沿岸步行进堡垒，再用两颗珍珠赶路、第三颗搁置。封存和导出均按原始回放中的投射物 UUID 清点下界投数。也可加 `--wait-for-piglins`。只有下层通道真有一群持剑猪灵时，这个选项才会让 Astra 先在与它们隔开的箱子平台上等仇恨消退，再走下层。

默认工作目录是用户主目录中的 `Astra-Practice-W113`。其中 `receipts` 保存每次尝试的结束原因、原生时间和回放路径，`split-history` 保存原生分段。成功时会先写入 `Astra-W113.zip`，随后调用原录像导出器在当前目录生成 `Astra-W113-practice-1080p60.mp4`。所有文件都在本机生成。按 Ctrl+C 可以请求游戏正常关闭。随后运行同一条命令即可接续这个训练目录。

| 目的 | 命令 |
| --- | --- |
| 从 16 区块起跑，第三颗珍珠出手后用快捷键降到 2，拿齐烈焰棒再调远 | `python practice.py --initial-view-distance 16` |
| 最多尝试五次，先只封存合格录像 | `python practice.py --attempts 5 --freeze-only` |
| 错过自己曾经实际达成的最快进下界时间就重开 | `python practice.py --reset-pb nether` |
| 同时考察进下界、进末地两个分段 | `python practice.py --reset-pb nether --reset-pb end` |
| 单独指定训练目录和最终视频路径 | `python practice.py --work "D:\Astra训练" --output "D:\视频\Astra.mp4"` |
| 检查旧练习的结局和两个计时 | `python practice.py --inspect "D:\Prism\instances\示例\.minecraft\saves\Astra-W113"` |
| 再次导出已经在本机封存的那一局 | `python render.py --take "D:\Astra训练\Astra-W113.zip" --output "D:\视频\Astra.mp4"` |

`--reset-pb` 只比较相同地图、模式和规则下此前真正完成的分段。首次尝试没有历史成绩，该项自然跳过。默认会让任何仍有机会达标的尝试继续。可用 `--prism`、`--prism-data`、`--profile` 指定现有启动器和本机账号，或用 `--ranked-jar`、`--replay-jar` 提供本机已有的对应版本模组。指定的模组仍须通过固定哈希校验。
