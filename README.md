# GrimLegacyAC for 1.7.10

> 面向 `Spigot 1.7.10` 的实验性反作弊插件实现。
> 目标不是把新版本 Grim 直接“移植”过来，而是在旧协议和旧服务端限制下，尽量复现 `packet-first`、`prediction-first`、`latency-aware` 这套设计思路。

## 项目定位

`legacy17` 是仓库中独立的 1.7.10 子模块，主要面向以下场景：

- 仍在维护 `Spigot 1.7.10` 的 PvP / 小游戏 / 老整合包服务器
- 需要比传统事件流反作弊更强的包级观测能力
- 需要在高延迟、低 TPS、珍珠位移、鱼竿拉扯等旧版本高误判场景下，保留一定解释性和调试能力

当前实现更接近“可持续演进的技术内核”，而不是一个已经完全稳定、覆盖全部边界条件的商用成品。上线前建议先在测试服压测和回归。

## 主要能力

- 无法使用的功能已经在预设配置中关闭
### 移动与状态类

- `Speed`
- `Fly`
- `Phase` _*无法使用_
- `Timer`
- `Jesus` _*无法使用_
- `InventoryMove`
- `NoSlow`
- `Prediction`
- `GroundSpoof`

### 战斗类

- `Reach`
- `KillAura`
- `AutoClicker` _*无法使用_
- `Velocity`
- `AimModulo360`
- `AimDuplicateLook`

### 交互与世界类

- `FastPlace`
- `FastBreak`
- `FastUse`
- `FarPlace` _*无法使用_
- `FabricatedPlace`
- `DuplicateRotPlace`
- `AirLiquidPlace` _*无法使用_
- `PositionPlace`
- `RotationPlace`
- `MultiPlace`
- `FarBreak`
- `RotationBreak` _*无法使用_
- `AirLiquidBreak` _*无法使用_
- `MultiBreak`

### 网络与异常包类

- `BadPacketsA/C/D/E/F/G/I/L/O/Q`
- `CrashA`
- 交易包 RTT 跟踪
- KeepAlive / transaction 对齐
- 包级战斗、放置、挖掘、世界状态采集

## 实现思路

### 1. Packet-first 输入链路

优先使用 `ProtocolLib` 直接监听 1.7.10 客户端关键包：

- 移动包：`FLYING` / `POSITION` / `LOOK` / `POSITION_LOOK`
- 战斗包：`USE_ENTITY`
- 对齐包：`TRANSACTION` / `KEEP_ALIVE`
- 方块交互：`BLOCK_PLACE` / `BLOCK_DIG`
- 服务端反馈：`POSITION` / `ENTITY_VELOCITY` / `BLOCK_CHANGE` / `MAP_CHUNK`

入口在 `src/main/java/ac/grim/legacyac/LegacyAntiCheatPlugin.java` 和 `src/main/java/ac/grim/legacyac/network/ProtocolLibBridgeManager.java`。

如果 `ProtocolLib` 不可用，或者包反射读取降级，插件会自动退回到 Netty / Bukkit 事件路径，而不是直接失效。

### 2. Movement pipeline

移动检查不是直接挂在 `PlayerMoveEvent` 上，而是走一条按帧处理的流水线：

`MovementFrame -> PlayerData 状态更新 -> 预算计算 -> Prediction -> Post checks -> Combat queue / fallback`

实现集中在 `src/main/java/ac/grim/legacyac/check/CheckManager.java` 和 `src/main/java/ac/grim/legacyac/check/MovementPipeline.java`。

这个链路包含几件关键事情：

- 优先消费包级 movement frame
- Bukkit `MoveEvent` 只在包数据 stale 或不可用时兜底
- 传送未对齐时会冻结部分检测，减少 TP / 珍珠相关误报
- prediction miss 时允许走保守的 `minimal-post` 检查，而不是整条链路直接空跑

### 3. 统一容差预算

旧版本最容易出问题的，不是“有没有检查项”，而是容差到底该怎么给。
`legacy17` 把 RTT、jitter、TPS、最近受击、液体、传送、边缘卡脚、鱼竿拉扯等因素统一收敛到 `ToleranceBudgetEngine`。

代码在 `src/main/java/ac/grim/legacyac/tolerance/ToleranceBudgetEngine.java`。

每一帧都会生成一份预算快照，供不同检查共享，主要输出：

- `movement allowance`
- `combat reach margin`
- `velocity response slack`

这意味着检查之间不再各自重复“猜”一份延迟容差，调参也更容易解释。

### 4. 1.7.10 预测模型

移动预测引擎会根据旧版本物理规则生成候选速度并比对真实移动结果，核心考虑：

- 1.7.10 的重力、摩擦和空气阻力
- 冲刺跳跃方向修正
- 药水效果
- 冰面、液体、梯子、台阶、边缘等场景
- 受击后速度窗口

实现见 `src/main/java/ac/grim/legacyac/prediction/LegacyPredictionEngine.java`。

### 5. Combat 回溯与命中盒

战斗链路不是只看一次事件，而是会结合：

- 攻击包触发时序
- 双方 transaction RTT
- 历史 hitbox 记录
- 动态回溯窗口

实现集中在 `src/main/java/ac/grim/legacyac/check/CombatPipeline.java`。

`Reach` 和 `KillAura` 共用这条链路，这样高延迟条件下的命中判定会更接近“当时看见的目标位置”。

## 调试与回归

插件不是只输出一个 flag，而是尽量保留证据链：

- `/glac debug <player>`：输出 pipeline 与预算调试信息
- `/glac profile <player>`：查看玩家各检查 VL、RTT、Velocity 窗口等状态
- `/glac dump <player>`：导出结构化信息，便于回归比对

对应 QA 场景已经整理在 `qa-scenarios.md`，例如：

- 斜跳加速
- 格挡移动
- 高处落地
- 鱼竿拉扯
- 液体受击
- 珍珠位移
- 传送后首包
- 库存移动

插件停服时还会输出一份 regression report，用来判断改动是否引入更高误报或更慢触发。

## 编译

### 前置要求

- `Java 8`
- 仓库根目录自带的 Gradle Wrapper
- 以下依赖文件存在于 `legacy17/libs/`
  - `spigot-server-1.7.10-R0.1-SNAPSHOT.jar`
  - `ProtocolLib1.7.jar`

### 构建命令

Linux / macOS:

```bash
./gradlew :legacy17:build
```

Windows:

```powershell
.\gradlew.bat :legacy17:build
```

产物输出目录：

```text
legacy17/build/libs/
```

## 安装

1. 将编译出的 `legacy17` jar 放入服务器 `plugins/` 目录。
2. 推荐同时安装与 1.7.10 匹配的 `ProtocolLib`，以启用完整包级链路。
3. 首次启动后检查生成的配置文件，再按服务器环境微调：
   - `pipeline.*`
   - `prediction.budget.*`
   - `transaction.*`
   - `adaptive-lag.*`
   - `checks.<CheckName>.*`

如果你要优先压低误报，建议先调：

- `checks.Speed.*`
- `checks.Reach.*`
- `checks.Velocity.*`
- `prediction.budget.*`

而不是一开始就把所有阈值整体抬高。

## 命令与权限

### 命令

- `/glac info`
- `/glac alerts`
- `/glac reload`
- `/glac profile <player>`
- `/glac debug <player>`
- `/glac dump <player>`

### 权限

- `grimlegacy.command`
- `grimlegacy.alerts`
- `grimlegacy.bypass`

## 兼容性与状态

- 目标平台：`Spigot 1.7.10`
- `ProtocolLib`：推荐安装，非强制
- 当前状态：实验性 / 开发中

如果你的目标是“老版本服也要尽量接近 Grim 的检测方式”，这个模块就是为这个方向准备的。
如果你的目标是“开箱即用、覆盖所有边界、几乎零调参”，那它目前还不是这个定位。
