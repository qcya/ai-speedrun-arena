# 第 29 周历史规则本地练习

这套可选配置让 Java 版 Minecraft 1.16.1 的单人存档实际加载当周的 MCSR Ranked 5.0.12。它复原第 29 周公开的七项赛道开关、维度种子和独立 RNG 种子，使用 Easy 生存。所有练习成绩只保存在本机。已发布的 N11 全程成绩使用本地原版生存规则。历史规则使用独立的计时记录。

## 启动

从 Modrinth 的 5.0.12 版本页取得原版 JAR，使用一个独立的 Fabric 1.16.1 游戏目录。下面从仓库根目录运行，先准备下界路线，再安装历史规则，两个命令都在首次进入存档之前执行。[历史版本](https://modrinth.com/mod/mcsr-ranked/version/5.0.12)

```bash
GAME_DIR="$PWD/week29-practice-instance"
python3 weekly/week29/prepare.py --game-dir "$GAME_DIR" --world-name Week29-Historical --strategy nether
python3 weekly/week29/historical-rules/historical_profile.py \
  --game-dir "$GAME_DIR" --world-name Week29-Historical \
  --ranked-jar "$HOME/Downloads/mcsrranked-5.0.12.jar"
```

随后从已正常登录的 Minecraft 启动器进入该实例，在「单人游戏」打开 Week29-Historical。安装器会核验原作者公布的 SHA-512 和未开档的原始模板。在首次开档前重复执行第二条命令会沿用同一份规则文件。每次重开使用新的存档名。配合本仓库的 PB 重开器时，将此目录下的 `level.dat` 作为模板，为启动的每个隔离实例提供同一份正版 Mod，并在课程标识中区分历史规则与原版路线。模板 SHA-256 为 `670932b263903dbecd6af27ef38b8af6b4c70f19d84d34f457a6b5ba69162246`。

使用成片专用双计时时，可在首次打开游戏前将 `speedrunigt:toggle_timer:false` 写入该独立实例的 `speedrunigt/options.txt`，关闭随 Mod 提供的另一套计时显示。若已经有该文件，保留其他设置，只更新这一个键。

## 末影龙机制

周赛数据给出的 flags 值为 76。位 2、3、6 开启，分别为堡垒怪物生成限制、固定龙路径和第一轮符合条件时进入降落。5.0.12 中的 `MixinEnderDragonFight.onSpawnDragon` 使用赛道的独立 RNG 流计算龙的初始朝向。`MixinHoldingPatternPhase.randomRedirect` 处理盘旋阶段 `tickInRange` 的第一处抽签。当前飞行路径走完时，该钩子让原版龙进入降落接近阶段，然后由游戏执行接近、降落、栖停。床的放置和角色攻击都不参与这次触发。

在独立的单人校准存档中，测试工具只将观察角色送往末地出生平台，随后一直站在原地。服务器读回了七项规则和三个独立种子。龙在世界 tick 21 处于盘旋，577 进入接近，1096 开始降落，1347 在祭坛处栖停，全程保持 200 HP。世界保存后包含原生的 `data/rng.dat`。这些 tick 是采样看到阶段的时间，受飞行路径和采样间隔影响。该校准只用于验证规则，与任何全程成绩独立。

[校准节选](calibration.txt) · [配置与来源](provenance.json) · [第 29 周官方数据](https://api.mcsrranked.com/weekly-race/29) · [MCSR Ranked 周赛说明](https://wiki.mcsrranked.com/gameplay/weekly_race) · [MCSR Ranked 随机机制](https://wiki.mcsrranked.com/gameplay/rng)
