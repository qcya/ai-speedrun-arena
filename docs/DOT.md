# 给 dot 的 Linux 实机复测任务

目标是在真实 Minecraft 1.16.1 的单人练习图中完成一次双选手赛，并交回原始成绩和第一人称录制。用现有桌面、已有正版账号、Python 3.9+ 和仓库提供的文件即可。不要调用付费模型 API。

1. 解压源码包，进入含 README 的目录，运行 `python3 -m unittest discover -s tests -v`。运行 `python3 -m arena --data-dir evidence serve` 可以先查看作者留下的实测轨迹。新比赛切换为默认数据目录。
2. 在 Prism 的添加实例页选择导入，选 `dist/AI-Speedrun-Arena-Prism.zip`。若本机有单独的 Fabric 1.16.1 实例，也可以复制随包 JAR 并运行 `tools/prepare_course.py`。从启动器打开真实的 Minecraft 文件夹，将该路径设为 `ARENA_GAME_DIR`。保持旧游戏实例和存档独立。
3. 启动新实例、打开 **AI Speedrun Arena | Practice Lab**。建议把执行终端放在另一窗口，用延迟启动或脚本调用 CLI，然后切回 Minecraft。进入存档后 `python3 -m arena state` 应显示 `game_version=1.16.1`、`ready=true`、`course_id=asa:course_v1` 和红床目标。暂停/失焦时会返回具体状态。
4. 运行 `python3 tools/tournament.py examples/bed.json examples/bed-wait.json --rounds 3`，记录每组成功数、失败数、最快 ticks。用 `show` 导出最快一次的原始 JSON。练习场自动清理床、掉落物和背包，并核对重置标记。
5. 在游戏内输入 `/function asa:block_test`，再运行 `python3 -m arena run examples/bed.json`。预期准星命中村民，动作被终止，背包没有红床。这个用例使用站定的村民。自然活动版本使用 `/function asa:villager_on` 和 `tools/tournament.py ... --villagers`，观察实际发生的拦截比例。
6. 切回 `/function asa:villager_off`。用 `python3 tools/tournament.py examples/craft-planks.json` 检查砍树、拾取、操作背包并合成四块木板。另可运行 `python3 tools/tournament.py examples/walk.json examples/sprint.json` 检查水平移动。直播桌板用 `python3 -m arena serve`，OBS 捕获 Minecraft 游戏窗口，并按需添加 `/?obs=1` 的浏览器源。
7. 返回版本、实际游戏目录、测试结果、各场 ID 和一段真实游戏窗口录屏。遇到问题请附 `doctor` 的输出、失败的结果与游戏日志末段，保留待对账记录。复测后可直接写自己的 `examples/<agent>.json` 加入竞赛。

GitHub 仓库名为 `qcya/ai-speedrun-arena`。独立运行全部流程不需要 GitHub 账号或云端排行榜。
